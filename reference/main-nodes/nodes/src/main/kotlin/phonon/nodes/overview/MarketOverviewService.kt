package phonon.nodes.overview

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.bukkit.Material
import phonon.nodes.Config
import phonon.nodes.Nodes
import phonon.nodes.market.MarketManager
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.collections.iterator

internal data class MarketSaleEvent(
    val timestamp: Long,
    val townName: String,
    val itemType: Material,
    val currency: Material,
    val itemAmount: Int,
    val listingUnitsSold: Int,
    val sellerRevenue: Double,
    val buyerPaid: Double,
)

internal object MarketOverviewService {
    private val lock = Any()
    private val salesHistory: ArrayList<MarketSaleEvent> = arrayListOf()
    private var server: HttpServer? = null
    private val overviewCache: MutableMap<String, Pair<Long, String>> = ConcurrentHashMap()

    /** Root folder for static market web assets: plugins/nodes/market/web/ */
    private val webDir: File
        get() = Paths.get(Config.pathPlugin, "market", "web").toFile()

    private val MIME_TYPES = mapOf(
        "html" to "text/html; charset=utf-8",
        "htm" to "text/html; charset=utf-8",
        "css" to "text/css; charset=utf-8",
        "js" to "application/javascript; charset=utf-8",
        "mjs" to "application/javascript; charset=utf-8",
        "json" to "application/json; charset=utf-8",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "svg" to "image/svg+xml",
        "ico" to "image/x-icon",
        "webp" to "image/webp",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "ttf" to "font/ttf",
        "txt" to "text/plain; charset=utf-8",
    )

    fun start() {
        if (!Config.marketOverviewEnabled) {
            return
        }

        loadHistory()

        val dir = webDir
        if (!dir.exists()) {
            dir.mkdirs()
            Nodes.logger?.info("Created market web assets directory at ${dir.path}")
        }

        try {
            val http = HttpServer.create(InetSocketAddress(Config.marketOverviewBindAddress, Config.marketOverviewWebPort), 0)
            http.createContext("/overview") { exchange -> handleOverview(exchange) }
            http.createContext("/health") { exchange -> handleHealth(exchange) }
            http.createContext("/nodes/market/overview") { exchange -> handleOverview(exchange) }
            http.createContext("/nodes/market/health") { exchange -> handleHealth(exchange) }
            http.createContext("/") { exchange -> handleStaticFile(exchange) }
            http.executor = Executors.newFixedThreadPool(2)
            http.start()
            server = http
            Nodes.logger?.info("Market overview web endpoint enabled on ${Config.marketOverviewBindAddress}:${Config.marketOverviewWebPort}")
        } catch (e: Exception) {
            Nodes.logger?.severe("Failed to start market overview web endpoint: ${e.message}")
        }
    }

    fun stop() {
        try {
            saveHistory()
        } catch (_: Exception) {
        }

        server?.stop(0)
        server = null
        overviewCache.clear()
    }

    fun recordSale(
        timestamp: Long,
        townName: String,
        itemType: Material,
        currency: Material,
        itemAmount: Int,
        listingUnitsSold: Int,
        sellerRevenue: Double,
        buyerPaid: Double,
    ) {
        if (!Config.marketOverviewEnabled) {
            return
        }

        synchronized(lock) {
            salesHistory.add(
                MarketSaleEvent(
                    timestamp = timestamp,
                    townName = townName,
                    itemType = itemType,
                    currency = currency,
                    itemAmount = itemAmount.coerceAtLeast(1),
                    listingUnitsSold = listingUnitsSold.coerceAtLeast(1),
                    sellerRevenue = sellerRevenue.coerceAtLeast(0.0),
                    buyerPaid = buyerPaid.coerceAtLeast(0.0),
                ),
            )
            pruneUnsafe(timestamp)
        }
        overviewCache.clear()
    }

    private fun handleStaticFile(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            sendText(exchange, 405, "text/plain; charset=utf-8", "Method not allowed")
            return
        }

        val rawPath = exchange.requestURI.path ?: "/"

        val relativePath = when {
            rawPath == "/" || rawPath == "/nodes/market" || rawPath == "/nodes/market/" -> "index.html"
            rawPath.startsWith("/nodes/market/") -> rawPath.removePrefix("/nodes/market/")
            else -> rawPath.removePrefix("/").ifEmpty { "index.html" }
        }

        val resolved = webDir.resolve(relativePath).canonicalFile
        if (!resolved.canonicalPath.startsWith(webDir.canonicalPath)) {
            sendText(exchange, 403, "text/plain; charset=utf-8", "Forbidden")
            return
        }

        val target = if (resolved.isDirectory) File(resolved, "index.html") else resolved

        if (!target.exists() || !target.isFile) {
            if (relativePath == "index.html") {
                sendText(exchange, 200, "text/html; charset=utf-8", marketPageHtml())
            } else {
                sendText(exchange, 404, "text/plain; charset=utf-8", "Not found")
            }
            return
        }

        val ext = target.extension.lowercase()
        val mime = MIME_TYPES[ext] ?: "application/octet-stream"
        sendBytes(exchange, 200, mime, target.readBytes())
    }

    private fun handleHealth(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            sendJson(exchange, 405, "{\"error\":\"method_not_allowed\"}")
            return
        }

        val now = System.currentTimeMillis()
        val body = JsonObject()
        body.addProperty("status", "ok")
        body.addProperty("time", now)
        body.addProperty("salesEvents", synchronized(lock) { salesHistory.size })
        body.addProperty("activeTownMarkets", MarketManager.getAllListingsSnapshot().size)
        sendJson(exchange, 200, body.toString())
    }

    private fun handleOverview(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            sendJson(exchange, 405, "{\"error\":\"method_not_allowed\"}")
            return
        }
        val queryKey = exchange.requestURI.rawQuery ?: ""
        val now = System.currentTimeMillis()
        val cacheMs = Config.marketOverviewResponseCacheMs.coerceAtLeast(0L)
        val cached = overviewCache[queryKey]
        if (cacheMs > 0L && cached != null && (now - cached.first) < cacheMs) {
            sendJson(exchange, 200, cached.second)
            return
        }

        val params = parseQuery(exchange.requestURI.rawQuery ?: "")
        val history = synchronized(lock) { salesHistory.toList() }
        val liveListings = MarketManager.getAllListingsSnapshot()
        val coverageFrom = history.minOfOrNull { it.timestamp } ?: now
        val coverageTo = history.maxOfOrNull { it.timestamp } ?: now

        val sortBy = (params["sortBy"] ?: Config.marketOverviewSortBy).lowercase().let {
            if (it == "units" || it == "transactions" || it == "revenue") it else "units"
        }

        data class TownAgg(
            var units: Int = 0,
            var transactions: Int = 0,
            var revenue: Double = 0.0,
            val itemUnits: HashMap<String, Int> = hashMapOf(),
        )
        data class ItemAgg(var units: Int = 0, var transactions: Int = 0, var revenue: Double = 0.0)
        data class CurrencyAgg(var transactions: Int = 0, var buyerVolume: Double = 0.0, var activeListings: Int = 0)

        val townAgg: LinkedHashMap<String, TownAgg> = linkedMapOf()
        val itemAgg: LinkedHashMap<String, ItemAgg> = linkedMapOf()
        val currencyAgg: LinkedHashMap<String, CurrencyAgg> = linkedMapOf()

        for (sale in history) {
            val soldItems = sale.itemAmount * sale.listingUnitsSold
            townAgg.computeIfAbsent(sale.townName) { TownAgg() }.apply {
                units += soldItems
                transactions += 1
                revenue += sale.sellerRevenue
                itemUnits.merge(sale.itemType.name, soldItems) { old, new -> old + new }
            }
            itemAgg.computeIfAbsent(sale.itemType.name) { ItemAgg() }.apply {
                units += soldItems
                transactions += 1
                revenue += sale.sellerRevenue
            }
            currencyAgg.computeIfAbsent(sale.currency.name) { CurrencyAgg() }.apply {
                transactions += 1
                buyerVolume += sale.buyerPaid
            }
        }

        val townRows = townAgg.entries.map { (town, agg) ->
            val uniqueSoldItems = agg.itemUnits.size
            val dominantShare = if (agg.units > 0) ((agg.itemUnits.maxOfOrNull { it.value } ?: 0).toDouble() / agg.units.toDouble()) else 0.0
            JsonObject().apply {
                addProperty("town", town)
                addProperty("unitsSold", agg.units)
                addProperty("transactions", agg.transactions)
                addProperty("revenue", (agg.revenue * 10.0).toInt() / 10.0)
                addProperty("uniqueSoldItems", uniqueSoldItems)
                addProperty("dominantItemSharePct", (dominantShare * 1000.0).toInt() / 10.0)
            } to agg
        }.sortedWith(
            when (sortBy) {
                "transactions" -> compareByDescending<Pair<JsonObject, TownAgg>> { it.second.transactions }.thenBy { it.first.get("town").asString }
                "revenue" -> compareByDescending<Pair<JsonObject, TownAgg>> { it.second.revenue }.thenBy { it.first.get("town").asString }
                else -> compareByDescending<Pair<JsonObject, TownAgg>> { it.second.units }.thenBy { it.first.get("town").asString }
            },
        ).take(Config.marketOverviewMaxRows)

        val topItems = itemAgg.entries
            .sortedByDescending { it.value.units }
            .take(Config.marketOverviewMaxRows)
            .map { (item, agg) ->
                JsonObject().apply {
                    addProperty("item", item)
                    addProperty("unitsSold", agg.units)
                    addProperty("transactions", agg.transactions)
                    addProperty("revenue", (agg.revenue * 10.0).toInt() / 10.0)
                }
            }

        val cheapestByItem = linkedMapOf<String, JsonObject>()
        val bestValueOffers: ArrayList<JsonObject> = arrayListOf()
        for ((town, listings) in liveListings) {
            for (listing in listings) {
                val itemKey = listing.itemStack.type.name
                val current = cheapestByItem[itemKey]
                val unitPrice = listing.price / listing.itemStack.amount.toDouble().coerceAtLeast(1.0)
                if (current == null || listing.price < current.get("price").asDouble) {
                    cheapestByItem[itemKey] = JsonObject().apply {
                        addProperty("item", itemKey)
                        addProperty("price", (listing.price * 10.0).toInt() / 10.0)
                        addProperty("unitPrice", (unitPrice * 1000.0).toInt() / 1000.0)
                        addProperty("currency", listing.currency.name)
                        addProperty("town", town)
                        addProperty("itemAmount", listing.itemStack.amount)
                        addProperty("listingUnits", listing.quantity)
                    }
                }

                bestValueOffers.add(
                    JsonObject().apply {
                        addProperty("item", itemKey)
                        addProperty("price", (listing.price * 10.0).toInt() / 10.0)
                        addProperty("unitPrice", (unitPrice * 1000.0).toInt() / 1000.0)
                        addProperty("currency", listing.currency.name)
                        addProperty("town", town)
                        addProperty("itemAmount", listing.itemStack.amount)
                        addProperty("listingUnits", listing.quantity)
                    },
                )
                currencyAgg.computeIfAbsent(listing.currency.name) { CurrencyAgg() }.apply {
                    activeListings += 1
                }
            }
        }

        val liveTownCatalog = liveListings.entries
            .map { (town, listings) ->
                val uniqueItems = listings.map { it.itemStack.type.name }.toSet().size
                JsonObject().apply {
                    addProperty("town", town)
                    addProperty("listingCount", listings.size)
                    addProperty("uniqueItems", uniqueItems)
                }
            }
            .sortedByDescending { it.get("listingCount").asInt }
            .take(Config.marketOverviewMaxRows)

        val townsJson = JsonArray()
        townRows.forEach { townsJson.add(it.first) }

        val itemsJson = JsonArray()
        topItems.forEach { itemsJson.add(it) }

        val cheapestJson = JsonArray()
        cheapestByItem.values.sortedBy { it.get("item").asString }.take(Config.marketOverviewMaxRows).forEach { cheapestJson.add(it) }

        val liveCatalogJson = JsonArray()
        liveTownCatalog.forEach { liveCatalogJson.add(it) }

        val fastMovingItemsJson = JsonArray()
        itemAgg.entries.sortedByDescending { it.value.transactions }.take(Config.marketOverviewMaxRows).forEach { (item, agg) ->
            fastMovingItemsJson.add(
                JsonObject().apply {
                    addProperty("item", item)
                    addProperty("transactions", agg.transactions)
                    addProperty("unitsSold", agg.units)
                },
            )
        }

        val bestValueJson = JsonArray()
        bestValueOffers
            .sortedWith(compareBy<JsonObject> { it.get("unitPrice").asDouble }.thenByDescending { it.get("listingUnits").asInt })
            .take(Config.marketOverviewMaxRows)
            .forEach { bestValueJson.add(it) }

        val currencyUsageJson = JsonArray()
        currencyAgg.entries
            .sortedByDescending { it.value.transactions + it.value.activeListings }
            .take(Config.marketOverviewMaxRows)
            .forEach { (currency, agg) ->
                currencyUsageJson.add(
                    JsonObject().apply {
                        addProperty("currency", currency)
                        addProperty("transactions", agg.transactions)
                        addProperty("activeListings", agg.activeListings)
                        addProperty("buyerVolume", (agg.buyerVolume * 10.0).toInt() / 10.0)
                    },
                )
            }

        val summary = JsonObject().apply {
            addProperty("townsWithSales", townAgg.size)
            addProperty("totalTransactions", history.size)
            addProperty("totalUnitsSold", history.sumOf { it.itemAmount * it.listingUnitsSold })
            addProperty("totalRevenue", (history.sumOf { it.sellerRevenue } * 10.0).toInt() / 10.0)
            addProperty("activeListings", liveListings.values.sumOf { it.size })
            addProperty("activeMarketTowns", liveListings.size)
            addProperty("activeUniqueItems", cheapestByItem.size)
            addProperty("historyDays", Config.marketOverviewHistoryDays)
        }

        val result = JsonObject()
        result.addProperty("coverageFrom", coverageFrom)
        result.addProperty("coverageTo", coverageTo)
        result.addProperty("generatedAt", now)
        result.addProperty("sortBy", sortBy)
        result.add("summary", summary)
        result.add("topTowns", townsJson)
        result.add("topItems", itemsJson)
        result.add("fastMovingItems", fastMovingItemsJson)
        result.add("cheapestOffers", cheapestJson)
        result.add("bestValueOffers", bestValueJson)
        result.add("liveTownCatalog", liveCatalogJson)
        result.add("currencyUsage", currencyUsageJson)
        val json = result.toString()
        if (cacheMs > 0L) {
            overviewCache[queryKey] = Pair(now, json)
        }
        sendJson(exchange, 200, json)
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) {
            return emptyMap()
        }
        return rawQuery.split("&").mapNotNull { pair ->
            val idx = pair.indexOf("=")
            if (idx <= 0) {
                return@mapNotNull null
            }
            val key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8)
            val value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8)
            Pair(key, value)
        }.toMap()
    }

    private fun sendJson(exchange: HttpExchange, code: Int, body: String) {
        sendText(exchange, code, "application/json; charset=utf-8", body)
    }

    private fun sendText(exchange: HttpExchange, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        sendBytes(exchange, code, contentType, bytes)
    }

    private fun sendBytes(exchange: HttpExchange, code: Int, contentType: String, bytes: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { os -> os.write(bytes) }
    }

    private fun pruneUnsafe(nowMillis: Long) {
        val maxAgeMs = Config.marketOverviewHistoryDays * 24L * 60L * 60L * 1000L
        val minTime = nowMillis - maxAgeMs
        while (salesHistory.isNotEmpty() && salesHistory.first().timestamp < minTime) {
            salesHistory.removeAt(0)
        }
    }

    private fun loadHistory() {
        migrateLegacyHistoryFile()
        val file = Config.pathMarketSalesHistory
        if (!Files.exists(file)) {
            return
        }

        try {
            val json = JsonParser.parseString(Files.readString(file)).asJsonObject
            val arr = json.getAsJsonArray("sales") ?: return
            val loaded: ArrayList<MarketSaleEvent> = arrayListOf()

            for (item in arr) {
                val obj = item.asJsonObject
                val ts = obj.get("time")?.asLong ?: continue
                val town = obj.get("town")?.asString ?: continue
                val itemType = Material.matchMaterial(obj.get("item")?.asString ?: "") ?: continue
                val currency = Material.matchMaterial(obj.get("currency")?.asString ?: "") ?: continue
                val itemAmount = obj.get("itemAmount")?.asInt ?: 1
                val listingUnits = obj.get("listingUnits")?.asInt ?: 1
                val sellerRevenue = obj.get("sellerRevenue")?.asDouble ?: 0.0
                val buyerPaid = obj.get("buyerPaid")?.asDouble ?: 0.0

                loaded.add(
                    MarketSaleEvent(
                        timestamp = ts,
                        townName = town,
                        itemType = itemType,
                        currency = currency,
                        itemAmount = itemAmount,
                        listingUnitsSold = listingUnits,
                        sellerRevenue = sellerRevenue,
                        buyerPaid = buyerPaid,
                    ),
                )
            }

            val now = System.currentTimeMillis()
            synchronized(lock) {
                salesHistory.clear()
                salesHistory.addAll(loaded.sortedBy { it.timestamp })
                pruneUnsafe(now)
            }
        } catch (e: Exception) {
            Nodes.logger?.warning("Failed to load market sales history: ${e.message}")
        }
    }

    private fun saveHistory() {
        val copy = synchronized(lock) { salesHistory.toList() }
        val root = JsonObject()
        val arr = JsonArray()
        for (sale in copy) {
            val obj = JsonObject()
            obj.addProperty("time", sale.timestamp)
            obj.addProperty("town", sale.townName)
            obj.addProperty("item", sale.itemType.name)
            obj.addProperty("currency", sale.currency.name)
            obj.addProperty("itemAmount", sale.itemAmount)
            obj.addProperty("listingUnits", sale.listingUnitsSold)
            obj.addProperty("sellerRevenue", sale.sellerRevenue)
            obj.addProperty("buyerPaid", sale.buyerPaid)
            arr.add(obj)
        }
        root.add("sales", arr)
        Files.createDirectories(Config.pathMarketSalesHistory.parent)
        Files.writeString(Config.pathMarketSalesHistory, root.toString())
    }

    private fun migrateLegacyHistoryFile() {
        if (Files.exists(Config.pathMarketSalesHistory)) {
            return
        }

        val legacyFile = Paths.get(Config.pathPlugin, "market_sales_history.json").normalize()
        if (!Files.exists(legacyFile)) {
            return
        }

        runCatching {
            Files.createDirectories(Config.pathMarketSalesHistory.parent)
            Files.copy(legacyFile, Config.pathMarketSalesHistory)
            Files.deleteIfExists(legacyFile)
            Nodes.logger?.info("Migrated market sales history to market/market_sales_history.json")
        }.onFailure { e ->
            Nodes.logger?.warning("Failed to migrate legacy market sales history: ${e.message}")
        }
    }

    private fun marketPageHtml(): String = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>${Config.marketOverviewPageTitle}</title>
          <style>
            :root {
              --bg: #071124;
              --bg2: #0a1a36;
              --panel: #0d2247cc;
              --line: #2a4f8d;
              --text: #eaf3ff;
              --muted: #9ec0ef;
              --ok: #8ce2ff;
            }
            body {
              margin: 0;
              padding: 24px;
              background:
                radial-gradient(circle at 20% 0%, #10346a 0%, transparent 40%),
                radial-gradient(circle at 80% 10%, #0b2a56 0%, transparent 35%),
                linear-gradient(180deg, var(--bg2), var(--bg));
              color: var(--text);
              font: 14px/1.45 "Segoe UI", Tahoma, Arial, sans-serif;
            }
            .wrap { max-width: 1380px; margin: 0 auto; }
            .hero {
              display: flex;
              align-items: center;
              gap: 16px;
              margin-bottom: 14px;
              padding: 14px 18px;
              border: 1px solid #2b4f87;
              border-radius: 14px;
              background: linear-gradient(160deg, #102b57c7, #0a1d3bcf);
              box-shadow: 0 12px 30px #00000059;
            }
            .hero img {
              width: min(420px, 46vw);
              max-height: 86px;
              object-fit: contain;
              filter: drop-shadow(0 8px 14px rgba(0,0,0,.35));
            }
            .hero h1 { margin: 0; font-size: 22px; }
            .hero p { margin: 4px 0 0; color: var(--muted); }
            .card {
              background: var(--panel);
              border: 1px solid var(--line);
              border-radius: 12px;
              padding: 14px 16px;
              margin-bottom: 14px;
              backdrop-filter: blur(2px);
              box-shadow: 0 10px 24px #00000033;
            }
            .controls {
              display: grid;
              grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
              gap: 10px;
              align-items: end;
            }
            .stats {
              display: grid;
              grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
              gap: 10px;
            }
            .stat {
              border: 1px solid var(--line);
              border-radius: 10px;
              padding: 10px;
              background: #0a1d3d99;
            }
            .stat .k { color: var(--muted); font-size: 12px; }
            .stat .v { font-weight: 700; font-size: 18px; margin-top: 4px; }
            label { display: block; color: var(--muted); margin-bottom: 4px; }
            input, select, button {
              width: 100%;
              box-sizing: border-box;
              background: #0a1d3d;
              color: var(--text);
              border: 1px solid var(--line);
              border-radius: 8px;
              padding: 8px 10px;
            }
            button {
              cursor: pointer;
              background: linear-gradient(180deg, #1a4f9c, #133e7f);
              border-color: #4f85d2;
              font-weight: 600;
            }
            .grid {
              display: grid;
              grid-template-columns: 1.2fr 1fr;
              gap: 14px;
            }
            table { width: 100%; border-collapse: collapse; }
            th, td { padding: 8px; border-bottom: 1px solid var(--line); text-align: left; }
            th { color: #d7e8ff; font-weight: 600; }
            .num { text-align: right; font-variant-numeric: tabular-nums; }
            .meta { color: var(--muted); margin-bottom: 10px; }
            .status { color: var(--ok); min-height: 20px; font-weight: 600; }
            @media (max-width: 980px) { .grid { grid-template-columns: 1fr; } }
          </style>
        </head>
        <body>
          <div class="wrap">
            <div class="hero">
              <img src="/logo.png" alt="Siege Logo">
              <div>
                <h1>${Config.marketOverviewPageTitle}</h1>
                <p>Live market intelligence for towns, offers, currencies, and demand.</p>
              </div>
            </div>

            <div class="card">
              <div class="controls">
                <div><button id="reloadBtn">Reload Data</button></div>
                <div>
                  <label for="sortBy">Top Town Sort</label>
                  <select id="sortBy">
                    <option value="units">Units Sold</option>
                    <option value="transactions">Transactions</option>
                    <option value="revenue">Revenue</option>
                  </select>
                </div>
                <div>
                  <label for="autoRefresh">Auto Refresh</label>
                  <select id="autoRefresh">
                    <option value="0">Off</option><option value="10">10s</option><option value="30">30s</option>
                    <option value="60">60s</option><option value="120">120s</option>
                  </select>
                </div>
              </div>
            </div>

            <div class="card">
              <div class="meta" id="meta">Loading...</div>
              <div class="status" id="status"></div>
              <div class="stats" id="summary"></div>
            </div>

            <div class="grid">
              <div class="card"><h3>Top Selling Towns</h3><div style="overflow:auto;"><table><thead><tr><th>Town</th><th class='num'>Units</th><th class='num'>Tx</th><th class='num'>Revenue</th></tr></thead><tbody id="townRows"></tbody></table></div></div>
              <div class="card"><h3>Most Sold Items</h3><div style="overflow:auto;"><table><thead><tr><th>Item</th><th class='num'>Units</th><th class='num'>Tx</th><th class='num'>Revenue</th></tr></thead><tbody id="itemRows"></tbody></table></div></div>
            </div>

            <div class="grid">
              <div class="card"><h3>Cheapest Active Offers (Live)</h3><div style="overflow:auto;"><table><thead><tr><th>Item</th><th class='num'>Price</th><th>Currency</th><th>Town</th><th class='num'>Amount</th><th class='num'>Units</th></tr></thead><tbody id="cheapRows"></tbody></table></div></div>
              <div class="card"><h3>What Each Town Is Selling (Live)</h3><div style="overflow:auto;"><table><thead><tr><th>Town</th><th class='num'>Listings</th><th class='num'>Unique Items</th></tr></thead><tbody id="catalogRows"></tbody></table></div></div>
            </div>

            <div class="grid">
              <div class="card"><h3>Best Value Offers (Per Item Unit)</h3><div style="overflow:auto;"><table><thead><tr><th>Item</th><th class='num'>Unit Price</th><th class='num'>Price</th><th>Currency</th><th>Town</th><th class='num'>Units</th></tr></thead><tbody id="valueRows"></tbody></table></div></div>
              <div class="card"><h3>Fast Moving Items</h3><div style="overflow:auto;"><table><thead><tr><th>Item</th><th class='num'>Tx</th><th class='num'>Units</th></tr></thead><tbody id="fastRows"></tbody></table></div></div>
            </div>

            <div class="grid">
              <div class="card"><h3>Currency Activity</h3><div style="overflow:auto;"><table><thead><tr><th>Currency</th><th class='num'>Tx</th><th class='num'>Active Listings</th><th class='num'>Buyer Volume</th></tr></thead><tbody id="currencyRows"></tbody></table></div></div>
            </div>
          </div>

          <script>
            function esc(s){ return String(s).replace(/[&<>"']/g, function(ch){ return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[ch]; }); }
            function fmt(ms){ return new Date(ms).toLocaleString(); }
            function num(n){ return Number(n).toLocaleString(); }
            function money(n){ return Number(n).toFixed(1); }
            function rowTown(r){ return "<tr><td>"+esc(r.town)+"</td><td class='num'>"+num(r.unitsSold)+"</td><td class='num'>"+num(r.transactions)+"</td><td class='num'>"+money(r.revenue)+"</td></tr>"; }
            function rowItem(r){ return "<tr><td>"+esc(r.item)+"</td><td class='num'>"+num(r.unitsSold)+"</td><td class='num'>"+num(r.transactions)+"</td><td class='num'>"+money(r.revenue)+"</td></tr>"; }
            function rowCheap(r){ return "<tr><td>"+esc(r.item)+"</td><td class='num'>"+money(r.price)+"</td><td>"+esc(r.currency)+"</td><td>"+esc(r.town)+"</td><td class='num'>"+num(r.itemAmount)+"</td><td class='num'>"+num(r.listingUnits)+"</td></tr>"; }
            function rowCat(r){ return "<tr><td>"+esc(r.town)+"</td><td class='num'>"+num(r.listingCount)+"</td><td class='num'>"+num(r.uniqueItems)+"</td></tr>"; }
            function rowValue(r){ return "<tr><td>"+esc(r.item)+"</td><td class='num'>"+Number(r.unitPrice).toFixed(3)+"</td><td class='num'>"+money(r.price)+"</td><td>"+esc(r.currency)+"</td><td>"+esc(r.town)+"</td><td class='num'>"+num(r.listingUnits)+"</td></tr>"; }
            function rowFast(r){ return "<tr><td>"+esc(r.item)+"</td><td class='num'>"+num(r.transactions)+"</td><td class='num'>"+num(r.unitsSold)+"</td></tr>"; }
            function rowCurrency(r){ return "<tr><td>"+esc(r.currency)+"</td><td class='num'>"+num(r.transactions)+"</td><td class='num'>"+num(r.activeListings)+"</td><td class='num'>"+money(r.buyerVolume)+"</td></tr>"; }
            function buildSummary(s){
              return [["Towns With Sales", s.townsWithSales],["Transactions", s.totalTransactions],["Units Sold", s.totalUnitsSold],["Revenue", Number(s.totalRevenue).toFixed(1)],["Active Listings", s.activeListings],["Active Market Towns", s.activeMarketTowns],["Active Unique Items", s.activeUniqueItems],["History Window (Days)", s.historyDays]].map(function(it){ return "<div class='stat'><div class='k'>"+esc(it[0])+"</div><div class='v'>"+esc(it[1])+"</div></div>"; }).join("");
            }
            async function loadData(){
              var status = document.getElementById("status");
              status.textContent = "Loading...";
              try {
                var sortBy = document.getElementById("sortBy").value || "units";
                var res = await fetch("/overview?sortBy=" + encodeURIComponent(sortBy));
                if (!res.ok) throw new Error("HTTP " + res.status);
                var data = await res.json();
                document.getElementById("meta").textContent = "Coverage: " + fmt(data.coverageFrom) + " -> " + fmt(data.coverageTo) + " | Generated: " + fmt(data.generatedAt);
                document.getElementById("summary").innerHTML = buildSummary(data.summary || {});
                document.getElementById("townRows").innerHTML = (data.topTowns || []).map(rowTown).join("") || "<tr><td colspan='4'>No sales data yet</td></tr>";
                document.getElementById("itemRows").innerHTML = (data.topItems || []).map(rowItem).join("") || "<tr><td colspan='4'>No sales data yet</td></tr>";
                document.getElementById("cheapRows").innerHTML = (data.cheapestOffers || []).map(rowCheap).join("") || "<tr><td colspan='6'>No active listings</td></tr>";
                document.getElementById("catalogRows").innerHTML = (data.liveTownCatalog || []).map(rowCat).join("") || "<tr><td colspan='3'>No active listings</td></tr>";
                document.getElementById("valueRows").innerHTML = (data.bestValueOffers || []).map(rowValue).join("") || "<tr><td colspan='6'>No active listings</td></tr>";
                document.getElementById("fastRows").innerHTML = (data.fastMovingItems || []).map(rowFast).join("") || "<tr><td colspan='3'>No sales data yet</td></tr>";
                document.getElementById("currencyRows").innerHTML = (data.currencyUsage || []).map(rowCurrency).join("") || "<tr><td colspan='4'>No currency activity yet</td></tr>";
                status.textContent = "Loaded";
              } catch (e) {
                status.textContent = "Failed to load data: " + e.message;
              }
            }
            document.getElementById("reloadBtn").addEventListener("click", function(){ loadData(); });
            document.getElementById("sortBy").addEventListener("change", function(){ loadData(); });

            (function init(){
              var defaultSort = "${Config.marketOverviewSortBy}";
              var defaultRefresh = "${Config.marketOverviewAutoRefreshSeconds}";
              document.getElementById("sortBy").value = defaultSort;

              var autoSel = document.getElementById("autoRefresh");
              var hasRefresh = false;
              for (var j = 0; j < autoSel.options.length; j++) {
                if (autoSel.options[j].value === defaultRefresh) { hasRefresh = true; break; }
              }
              if (!hasRefresh) {
                var autoOpt = document.createElement("option");
                autoOpt.value = defaultRefresh;
                autoOpt.textContent = defaultRefresh + "s";
                autoSel.appendChild(autoOpt);
              }
              autoSel.value = defaultRefresh;

              loadData();

              var intervalMs = Number(defaultRefresh) * 1000;
              if (intervalMs > 0) {
                setInterval(function(){
                  loadData();
                }, intervalMs);
              }
            })();
          </script>
        </body>
        </html>
    """.trimIndent()
}
