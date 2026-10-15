package com.stitchsocial.club.foundation

/**
 * A blocklist of usernames nobody may self-claim: platform-core handles plus
 * major brands, leagues, and teams (impersonation / squatting protection).
 * Verified/official accounts can still be assigned these out-of-band.
 *
 * iOS parity — The Core/ReservedUsernames.swift, same lists. Android signup
 * had no blocklist at all, so "@nike" was a reserved name on one platform and
 * a free one on the other.
 *
 * Lookup is normalized the same way on both (lowercased, stripped of
 * everything but letters and digits) so "Coca-Cola", "coca_cola", "CocaCola"
 * all resolve to the reserved "cocacola".
 */
object ReservedUsernames {

    /** True if [raw] normalizes to a reserved handle. */
    fun isReserved(raw: String): Boolean = all.contains(normalize(raw))

    /** Collapse a handle to comparable form: lowercase, letters and digits only. */
    fun normalize(raw: String): String = raw.lowercase().filter { it.isLetterOrDigit() }

    private val platformCore = listOf(
        "stitchsocial", "stitch", "stitchbeta", "stitchcreator", "stitchpro",
    )

    private val socialMedia = listOf(
        "facebook", "instagram", "threads", "whatsapp", "tiktok", "snapchat",
        "x", "twitter", "youtube", "twitch", "discord", "reddit", "pinterest",
        "linkedin", "telegram", "wechat", "kik",
    )

    private val techCompanies = listOf(
        "apple", "google", "alphabet", "amazon", "microsoft", "meta", "tesla",
        "spacex", "nvidia", "oracle", "ibm", "intel", "adobe", "salesforce",
        "netflix", "spotify", "uber", "lyft", "airbnb", "paypal", "square",
        "block", "shopify", "stripe", "zoom", "slack", "atlassian", "cloudflare",
        "snowflake", "databricks", "openai", "anthropic",
    )

    private val retailCompanies = listOf(
        "walmart", "bestbuy", "target", "costco", "homedepot", "lowes", "kroger",
        "publix", "walgreens", "cvs", "dollargeneral", "dollarstore", "tjmaxx",
        "rossstores", "macys", "nordstrom", "samsclub", "ikea", "aldi",
        "wholefoods", "traderjoes", "sephora", "ulta", "gamestop",
    )

    private val autoAerospace = listOf(
        "ford", "gm", "chevrolet", "cadillac", "buick", "chrysler", "dodge",
        "jeep", "ram", "toyota", "lexus", "honda", "acura", "nissan", "infiniti",
        "hyundai", "kia", "subaru", "mazda", "volkswagen", "audi", "porsche",
        "bmw", "mercedesbenz", "volvo", "jaguar", "landrover", "rivian", "lucid",
        "boeing", "airbus", "lockheedmartin", "raytheon", "northropgrumman",
        "blueorigin", "virgingalactic",
    )

    private val financeBanking = listOf(
        "jpmorgan", "bankofamerica", "wellsfargo", "citibank", "citi",
        "goldmansachs", "morganstanley", "charlesschwab", "fidelity", "vanguard",
        "blackrock", "americanexpress", "visa", "mastercard", "chime", "sofi",
        "robinhood", "cashapp", "venmo", "paypal",
    )

    private val foodBeverage = listOf(
        "cocacola", "pepsico", "starbucks", "mcdonalds", "burgerking", "wendys",
        "chipotle", "dominos", "pizzahut", "kfc", "tacobell", "subway", "dunkin",
        "panera", "chickfila", "popeyes", "jackinthebox", "fiveguys", "inout",
    )

    private val mediaEntertainment = listOf(
        "espn", "sportscenter", "foxsports", "nbcsports", "cbs", "abc", "disney",
        "warnerbros", "wbd", "hbo", "max", "hulu", "paramount", "showtime", "tnt",
        "tbs", "bleacherreport", "complex", "tmz", "netflix", "primevideo",
        "appletv", "roku", "crunchyroll",
    )

    private val gamingCompanies = listOf(
        "playstation", "xbox", "nintendo", "steam", "valve", "epicgames",
        "fortnite", "ea", "electronicarts", "activision", "blizzard", "riotgames",
        "leagueoflegends", "ubisoft", "rockstar", "take2", "2k", "bethesda",
        "squareenix", "capcom", "bandainamco",
    )

    private val sportsLeagues = listOf(
        "nba", "wnba", "nfl", "mlb", "nhl", "mls", "ncaa", "fifa", "uefa",
        "premierleague", "laliga", "bundesliga", "seriA", "ligue1", "ufc", "wwe",
        "aew", "pga", "lpga", "nascar", "indycar", "formula1", "f1",
    )

    private val nbaTeams = listOf(
        "hawks", "celtics", "nets", "hornets", "bulls", "cavaliers", "mavericks",
        "nuggets", "pistons", "warriors", "rockets", "pacers", "clippers",
        "lakers", "grizzlies", "heat", "bucks", "timberwolves", "pelicans",
        "knicks", "thunder", "magic", "sixers", "suns", "blazers", "kings",
        "spurs", "raptors", "jazz", "wizards",
    )

    private val wnbaTeams = listOf(
        "dream", "sky", "fever", "sparks", "lynx", "liberty", "mercury", "storm",
        "aces", "wings", "mystics", "sun", "stars",
    )

    private val nflTeams = listOf(
        "cardinals", "falcons", "ravens", "bills", "panthers", "bears", "bengals",
        "browns", "cowboys", "broncos", "lions", "packers", "texans", "colts",
        "jaguars", "chiefs", "raiders", "chargers", "rams", "dolphins", "vikings",
        "patriots", "saints", "giants", "jets", "eagles", "steelers", "niners",
        "seahawks", "buccaneers", "titans", "commanders",
    )

    private val mlbTeams = listOf(
        "orioles", "redsox", "yankees", "rays", "bluejays", "whitesox",
        "guardians", "tigers", "royals", "twins", "astros", "angels", "mariners",
        "rangers", "braves", "marlins", "mets", "phillies", "nationals", "cubs",
        "reds", "brewers", "pirates", "cardinals", "diamondbacks", "rockies",
        "dodgers", "padres", "giants",
    )

    private val nhlTeams = listOf(
        "ducks", "coyotes", "bruins", "sabres", "flames", "hurricanes",
        "blackhawks", "avalanche", "bluejackets", "stars", "redwings", "oilers",
        "panthers", "kings", "wild", "canadiens", "predators", "devils",
        "islanders", "rangers", "senators", "flyers", "penguins", "sharks",
        "kraken", "blues", "lightning", "mapleleafs", "canucks", "goldenknights",
        "capitals", "jets",
    )

    private val mlsTeams = listOf(
        "atlantaunited", "austinfc", "chicagofire", "fccincinnati",
        "coloradorapids", "columbuscrew", "dcunited", "fcdallas", "houstondynamo",
        "intermiami", "lafc", "lagalaxy", "minnesotatunited", "cfmontreal",
        "nashvillesc", "newenglandrevolution", "nycfc", "newyorkredbulls",
        "orlandocity", "philadelphiaunion", "portlandtimbers", "realsaltlake",
        "sanjoseshockers", "seattlesounders", "sportingkc", "torontofc",
        "vancouverwhitecaps", "stlcity", "charlottefc",
    )

    /**
     * Flattened, normalized reserved set. Declared after the lists it reads —
     * object properties initialize top to bottom. normalize() is applied so
     * future edits don't need to be pre-cleaned.
     */
    val all: Set<String> = listOf(
        platformCore, socialMedia, techCompanies, retailCompanies,
        autoAerospace, financeBanking, foodBeverage, mediaEntertainment,
        gamingCompanies, sportsLeagues, nbaTeams, wnbaTeams, nflTeams,
        mlbTeams, nhlTeams, mlsTeams,
    ).flatten().map(::normalize).toSet()
}
