/*
 * HypeCoinBillingManager.kt - GOOGLE PLAY BILLING MANAGER
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services — In-app purchase via Google Play Billing Library 6+
 * Dependencies: HypeCoinModel.kt, HypeCoinService.kt, FirebaseSchema.kt
 *
 * ANDROID ONLY — no iOS equivalent.
 * iOS purchases through web/Stripe to avoid Apple 30% cut.
 * Android uses Play Billing directly (Google takes 15% for first $1M/year).
 *
 * FLOW:
 *   1. loadProducts()       — query Play Console for product details (1 call at init)
 *   2. launchPurchase()     — open Play purchase sheet (Activity required)
 *   3. onPurchaseResult()   — called by BillingClient.PurchasesUpdatedListener
 *   4. handlePurchase()     — acknowledge + credit Firestore via HypeCoinService
 *   5. HypeCoinCoordinator  — real-time Firestore listener detects balance change → UI updates
 *
 * CACHING:
 *   - ProductDetails cached in memory after first loadProducts() call.
 *     Re-queried only on BillingClient reconnect or explicit refresh.
 *     Add PRODUCT_DETAILS entry to CacheType in CoreTypes.kt if needed.
 *   - Pending purchases re-processed on every BillingClient reconnect
 *     to handle interrupted flows (app killed mid-purchase).
 *
 * BATCHING:
 *   - No batching needed here. Each purchase is one Play transaction → one
 *     creditPurchase() call. Tips/subscriptions are handled by HypeCoinService.
 *
 * PLAY CONSOLE SETUP (required before this code works):
 *   1. Create in-app products in Play Console → Monetize → In-app products
 *   2. Product IDs must match HypeCoinPackage.playBillingProductId:
 *      "hype_coins_starter", "hype_coins_basic", "hype_coins_plus",
 *      "hype_coins_pro", "hype_coins_max"
 *   3. Set prices matching HypeCoinPackage.price (USD)
 *   4. Add billing permission to AndroidManifest.xml:
 *      <uses-permission android:name="com.android.vending.BILLING" />
 *   5. Add dependency to build.gradle:
 *      implementation("com.android.billingclient:billing-ktx:6.2.1")
 */

package com.stitchsocial.club.services

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import com.stitchsocial.club.foundation.CoinError
import com.stitchsocial.club.foundation.HypeCoinPackage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HypeCoinBillingManager private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: HypeCoinBillingManager? = null

        fun getInstance(context: Context): HypeCoinBillingManager =
            instance ?: synchronized(this) {
                instance ?: HypeCoinBillingManager(context.applicationContext).also { instance = it }
            }
    }

    // MARK: - Dependencies

    private val coinService = HypeCoinService.shared
    private val scope       = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // MARK: - State

    private val _billingState = MutableStateFlow<BillingState>(BillingState.Disconnected)
    val billingState: StateFlow<BillingState> = _billingState.asStateFlow()

    private val _purchaseState = MutableStateFlow<PurchaseState>(PurchaseState.Idle)
    val purchaseState: StateFlow<PurchaseState> = _purchaseState.asStateFlow()

    /** Cached ProductDetails keyed by productId — populated after loadProducts() */
    private val productDetailsCache = mutableMapOf<String, ProductDetails>()

    /** UserID set by HypeCoinCoordinator.configure() — needed to credit purchase */
    var currentUserID: String? = null

    // MARK: - BillingClient Setup

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    scope.launch { handlePurchase(purchase) }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                println("ℹ️ BILLING: Purchase cancelled by user")
                _purchaseState.value = PurchaseState.Cancelled
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                // Can happen if a previous purchase wasn't acknowledged — reprocess
                println("⚠️ BILLING: Item already owned — checking pending purchases")
                scope.launch { reprocessPendingPurchases() }
            }
            else -> {
                val error = "Billing error ${billingResult.responseCode}: ${billingResult.debugMessage}"
                println("❌ BILLING: $error")
                _purchaseState.value = PurchaseState.Failed(error)
            }
        }
    }

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .build()

    // MARK: - Connect

    /**
     * Connect to Play Billing and load product details.
     * Call from HypeCoinCoordinator.configure() after user signs in.
     */
    fun connect() {
        if (billingClient.isReady) return

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    println("✅ BILLING: Connected to Google Play")
                    _billingState.value = BillingState.Connected
                    scope.launch {
                        loadProducts()
                        reprocessPendingPurchases()
                    }
                } else {
                    println("❌ BILLING: Setup failed - ${billingResult.debugMessage}")
                    _billingState.value = BillingState.Error(billingResult.debugMessage)
                }
            }

            override fun onBillingServiceDisconnected() {
                println("⚠️ BILLING: Disconnected — will retry on next operation")
                _billingState.value = BillingState.Disconnected
                // BillingClient retries automatically; no manual reconnect needed
            }
        })
    }

    /** Disconnect and clean up. Call from HypeCoinCoordinator.disconnect(). */
    fun disconnect() {
        billingClient.endConnection()
        productDetailsCache.clear()
        scope.cancel()
        _billingState.value = BillingState.Disconnected
        println("🔌 BILLING: Disconnected")
    }

    // MARK: - Load Products

    /**
     * Query Play Console for all HypeCoin product details.
     * Results cached in productDetailsCache — no repeat queries needed.
     *
     * CACHING: ProductDetails cached in memory for session lifetime.
     * Re-queried only on reconnect or explicit refreshProducts() call.
     */
    private suspend fun loadProducts() {
        val productList = HypeCoinPackage.entries.map { pkg ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(pkg.playBillingProductId)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        val result = billingClient.queryProductDetails(params)

        if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            result.productDetailsList?.forEach { details ->
                productDetailsCache[details.productId] = details
            }
            println("✅ BILLING: Loaded ${productDetailsCache.size}/${HypeCoinPackage.entries.size} products")
        } else {
            println("❌ BILLING: Failed to load products - ${result.billingResult.debugMessage}")
        }
    }

    /** Force-refresh product details (e.g. after reconnect or price change). */
    suspend fun refreshProducts() {
        productDetailsCache.clear()
        loadProducts()
    }

    // MARK: - Get Product Details

    /**
     * Return ProductDetails for a package — null if not loaded yet.
     * UI uses this to display Play-formatted price (respects locale/currency).
     */
    fun getProductDetails(pkg: HypeCoinPackage): ProductDetails? =
        productDetailsCache[pkg.playBillingProductId]

    /**
     * Return all loaded ProductDetails in package order.
     * Use for displaying purchase UI.
     */
    fun getAllProductDetails(): List<Pair<HypeCoinPackage, ProductDetails>> =
        HypeCoinPackage.entries.mapNotNull { pkg ->
            productDetailsCache[pkg.playBillingProductId]?.let { Pair(pkg, it) }
        }

    // MARK: - Launch Purchase

    /**
     * Open Play purchase sheet for a given package.
     * Requires a live Activity — call from a ViewModel with Activity reference
     * or use ActivityResultLauncher pattern.
     *
     * Result delivered to PurchasesUpdatedListener → handlePurchase().
     */
    fun launchPurchase(activity: Activity, pkg: HypeCoinPackage): Boolean {
        if (!billingClient.isReady) {
            println("❌ BILLING: Client not ready — reconnecting")
            connect()
            _purchaseState.value = PurchaseState.Failed("Billing not ready. Please try again.")
            return false
        }

        val details = productDetailsCache[pkg.playBillingProductId]
        if (details == null) {
            println("❌ BILLING: ProductDetails not loaded for ${pkg.playBillingProductId}")
            _purchaseState.value = PurchaseState.Failed("Product not available. Please try again.")
            return false
        }

        val offerToken = details.oneTimePurchaseOfferDetails?.let { "" } ?: ""

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .build()
        )

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        val result = billingClient.launchBillingFlow(activity, billingFlowParams)

        return if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            println("🛒 BILLING: Launched purchase flow for ${pkg.displayName}")
            _purchaseState.value = PurchaseState.Purchasing(pkg)
            true
        } else {
            println("❌ BILLING: Failed to launch - ${result.debugMessage}")
            _purchaseState.value = PurchaseState.Failed(result.debugMessage)
            false
        }
    }

    // MARK: - Handle Purchase

    /**
     * Verify, acknowledge, and credit a completed Play purchase.
     *
     * Flow:
     *   1. Check purchase state is PURCHASED (not PENDING)
     *   2. Acknowledge with Play (required within 3 days or Play auto-refunds)
     *   3. Call HypeCoinService.creditPurchase() → writes to Firestore
     *   4. HypeCoinCoordinator's Firestore listener detects balance change → UI updates
     *
     * SAFETY:
     *   - purchaseToken is unique per transaction — HypeCoinService duplicate-checks it
     *   - Called for each purchase in the list, including re-delivered purchases on reconnect
     */
    private suspend fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            println("ℹ️ BILLING: Purchase pending — waiting for completion")
            _purchaseState.value = PurchaseState.Pending
            return
        }

        val userID = currentUserID
        if (userID == null) {
            println("❌ BILLING: No userID set — cannot credit purchase")
            _purchaseState.value = PurchaseState.Failed("User not signed in")
            return
        }

        // Resolve package from productId
        val productId = purchase.products.firstOrNull()
        val pkg       = HypeCoinPackage.entries.firstOrNull { it.playBillingProductId == productId }

        if (pkg == null) {
            println("❌ BILLING: Unknown productId: $productId")
            _purchaseState.value = PurchaseState.Failed("Unknown product")
            return
        }

        // Acknowledge with Play — must happen within 3 days
        if (!purchase.isAcknowledged) {
            val ackParams = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()

            val ackResult = billingClient.acknowledgePurchase(ackParams)

            if (ackResult.responseCode != BillingClient.BillingResponseCode.OK) {
                println("❌ BILLING: Acknowledge failed - ${ackResult.debugMessage}")
                _purchaseState.value = PurchaseState.Failed("Purchase acknowledgment failed")
                return
            }

            println("✅ BILLING: Purchase acknowledged with Play")
        }

        // Credit Firestore via HypeCoinService
        try {
            coinService.creditPurchase(
                userID           = userID,
                coins            = pkg.coins,
                purchaseToken    = purchase.purchaseToken,
                packageRawValue  = pkg.rawValue
            )

            println("💰 BILLING: Credited ${pkg.coins} coins to $userID for ${pkg.displayName}")
            _purchaseState.value = PurchaseState.Success(pkg)

        } catch (e: Exception) {
            println("❌ BILLING: Credit failed - ${e.message}")
            _purchaseState.value = PurchaseState.Failed(e.message ?: "Credit failed")
        }
    }

    // MARK: - Reprocess Pending Purchases

    /**
     * Query Play for any unacknowledged purchases and reprocess them.
     * Called on every BillingClient reconnect to recover interrupted flows.
     *
     * Handles: app killed after Play confirmed but before Firestore credited,
     *          network failures mid-purchase, device reboots.
     */
    private suspend fun reprocessPendingPurchases() {
        if (!billingClient.isReady) return

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        val result = billingClient.queryPurchasesAsync(params)

        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            println("⚠️ BILLING: Could not query pending purchases")
            return
        }

        val unprocessed = result.purchasesList.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }

        if (unprocessed.isNotEmpty()) {
            println("🔄 BILLING: Reprocessing ${unprocessed.size} pending purchase(s)")
            unprocessed.forEach { purchase -> handlePurchase(purchase) }
        }
    }

    // MARK: - Check Billing Availability

    /**
     * Check if Play Billing is available on this device.
     * Some Android devices (e.g. non-GMS) don't have Play Store.
     */
    fun isBillingAvailable(): Boolean = billingClient.isReady

    suspend fun checkBillingAvailability(): Boolean {
        if (!billingClient.isReady) connect()
        // Give connection a moment
        delay(500)
        return billingClient.isReady
    }
}

// MARK: - Billing State

sealed class BillingState {
    object Disconnected : BillingState()
    object Connected    : BillingState()
    data class Error(val message: String) : BillingState()
}

// MARK: - Purchase State

sealed class PurchaseState {
    object Idle      : PurchaseState()
    object Pending   : PurchaseState()
    object Cancelled : PurchaseState()
    data class Purchasing(val pkg: HypeCoinPackage) : PurchaseState()
    data class Success(val pkg: HypeCoinPackage)    : PurchaseState()
    data class Failed(val message: String)          : PurchaseState()
}