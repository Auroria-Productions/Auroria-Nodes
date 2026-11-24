package phonon.nodes.overview

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import phonon.nodes.Config
import phonon.nodes.Nodes
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

internal data class RallySnapshot(
    val timestamp: Long,
    val nationCounts: Map<String, Int>,
)

internal object RallyOverviewService {
    private val lock = Any()
    private val snapshots: ArrayList<RallySnapshot> = arrayListOf()
    private var lastSnapshotTime: Long = 0L
    private var server: HttpServer? = null
    private val overviewCache: MutableMap<String, Pair<Long, String>> = ConcurrentHashMap()

    /** Root folder for static rally web assets: plugins/nodes/rally/web/ */
    private val webDir: File
        get() = Paths.get(Config.pathPlugin, "rally", "web").toFile()

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
        if (!Config.rallyOverviewEnabled) {
            return
        }

        loadHistory()

        val dir = webDir
        if (!dir.exists()) {
            dir.mkdirs()
            Nodes.logger?.info("Created rally web assets directory at ${dir.path}")
        }

        try {
            val http = HttpServer.create(InetSocketAddress(Config.rallyOverviewBindAddress, Config.rallyOverviewWebPort), 0)
            http.createContext("/overview") { exchange -> handleOverview(exchange) }
            http.createContext("/health") { exchange -> handleHealth(exchange) }
            http.createContext("/nodes/rally/overview") { exchange -> handleOverview(exchange) }
            http.createContext("/nodes/rally/health") { exchange -> handleHealth(exchange) }
            http.createContext("/") { exchange -> handleStaticFile(exchange) }
            http.executor = Executors.newFixedThreadPool(2)
            http.start()
            server = http
            Nodes.logger?.info("Rally overview web endpoint enabled on ${Config.rallyOverviewBindAddress}:${Config.rallyOverviewWebPort}")
            Nodes.logger?.info(
                "Rally overview config: sample=${Config.rallyOverviewSamplePeriodMs}ms history=${Config.rallyOverviewHistoryDays}d sort=${Config.rallyOverviewSortBy} rows=${Config.rallyOverviewMaxRows}",
            )
        } catch (e: Exception) {
            Nodes.logger?.severe("Failed to start rally overview web endpoint: ${e.message}")
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

    fun recordSnapshot(nowMillis: Long) {
        if (!Config.rallyOverviewEnabled) {
            return
        }
        if (nowMillis < lastSnapshotTime + Config.rallyOverviewSamplePeriodMs) {
            return
        }

        val currentCounts = Nodes.nations.values.associate { it.name to it.playersOnline.size }
        synchronized(lock) {
            snapshots.add(RallySnapshot(nowMillis, currentCounts))
            lastSnapshotTime = nowMillis
            pruneUnsafe(nowMillis)
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
            rawPath == "/" || rawPath == "/nodes/rally" || rawPath == "/nodes/rally/" -> "index.html"
            rawPath.startsWith("/nodes/rally/") -> rawPath.removePrefix("/nodes/rally/")
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
                sendText(exchange, 200, "text/html; charset=utf-8", rallyPageHtml())
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
            sendJson(exchange, 405, """{"error":"method_not_allowed"}""")
            return
        }
        val now = System.currentTimeMillis()
        val body = JsonObject()
        body.addProperty("status", "ok")
        body.addProperty("time", now)
        body.addProperty("snapshotCount", synchronized(lock) { snapshots.size })
        sendJson(exchange, 200, body.toString())
    }

    private fun handleOverview(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            sendJson(exchange, 405, """{"error":"method_not_allowed"}""")
            return
        }
        val queryKey = exchange.requestURI.rawQuery ?: ""
        val now = System.currentTimeMillis()
        val cacheMs = Config.rallyOverviewResponseCacheMs.coerceAtLeast(0L)
        val cached = overviewCache[queryKey]
        if (cacheMs > 0L && cached != null && (now - cached.first) < cacheMs) {
            sendJson(exchange, 200, cached.second)
            return
        }

        val params = parseQuery(exchange.requestURI.rawQuery ?: "")
        val range = parseRange(params, now) ?: run {
            sendJson(
                exchange,
                400,
                """{"error":"invalid_range","hint":"Use date=YYYY-MM-DD or from/to (epoch ms or ISO-8601) or lastHours=<n>"}""",
            )
            return
        }

        val from = range.first
        val to = range.second
        if (from >= to) {
            sendJson(exchange, 400, """{"error":"invalid_range","hint":"from must be < to"}""")
            return
        }

        val snapshotCopy = synchronized(lock) {
            snapshots.filter { it.timestamp in from..to }
        }

        val latestCounts = synchronized(lock) { snapshots.lastOrNull()?.nationCounts ?: emptyMap() }
        val nationNames = linkedSetOf<String>()
        for (snap in snapshotCopy) {
            nationNames.addAll(snap.nationCounts.keys)
        }
        nationNames.addAll(latestCounts.keys)

        val nationJson = JsonArray()
        val sortBy = (params["sortBy"] ?: Config.rallyOverviewSortBy).lowercase()
        val rows = nationNames.map { nationName ->
            val current = latestCounts[nationName] ?: 0
            val sum = snapshotCopy.sumOf { snap -> snap.nationCounts[nationName] ?: 0 }
            val avg = if (snapshotCopy.isEmpty()) 0.0 else (sum.toDouble() / snapshotCopy.size.toDouble())
            val peak = snapshotCopy.maxOfOrNull { snap -> snap.nationCounts[nationName] ?: 0 } ?: 0
            val rowObj = JsonObject().apply {
                addProperty("nation", nationName)
                addProperty("avgRally", ((avg * 10.0).toInt() / 10.0))
                addProperty("peakRally", peak)
                addProperty("currentRally", current)
            }
            Quadruple(
                nationName,
                rowObj,
                avg,
                peak.toDouble(),
                current.toDouble(),
            )
        }.sortedWith(
            when (sortBy) {
                "peak" -> compareByDescending<Quadruple<String, JsonObject, Double, Double, Double>> { it.fourth }.thenBy { it.first }
                "current" -> compareByDescending<Quadruple<String, JsonObject, Double, Double, Double>> { it.fifth }.thenBy { it.first }
                else -> compareByDescending<Quadruple<String, JsonObject, Double, Double, Double>> { it.third }.thenBy { it.first }
            },
        ).take(Config.rallyOverviewMaxRows)

        for (row in rows) {
            nationJson.add(row.second)
        }

        val result = JsonObject()
        result.addProperty("from", from)
        result.addProperty("to", to)
        result.addProperty("generatedAt", now)
        result.addProperty("sortBy", sortBy)
        result.add("nations", nationJson)

        val json = result.toString()
        if (cacheMs > 0L) {
            overviewCache[queryKey] = Pair(now, json)
        }
        sendJson(exchange, 200, json)
    }

    private fun parseRange(params: Map<String, String>, now: Long): Pair<Long, Long>? {
        val dateValue = params["date"]
        if (dateValue != null) {
            return try {
                val day = LocalDate.parse(dateValue)
                val zone = ZoneId.systemDefault()
                val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
                val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
                Pair(from, to)
            } catch (_: DateTimeParseException) {
                null
            }
        }

        val fromRaw = params["from"]
        val toRaw = params["to"]
        if (fromRaw != null && toRaw != null) {
            val from = parseMillisOrIso(fromRaw) ?: return null
            val to = parseMillisOrIso(toRaw) ?: return null
            return Pair(from, to)
        }

        val lastHours = params["lastHours"]?.toLongOrNull()?.coerceAtLeast(1L) ?: 24L
        return Pair(now - (lastHours * 60L * 60L * 1000L), now)
    }

    private fun parseMillisOrIso(raw: String): Long? {
        raw.toLongOrNull()?.let { return it }
        return try {
            Instant.parse(raw).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
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
        val maxAgeMs = Config.rallyOverviewHistoryDays * 24L * 60L * 60L * 1000L
        val minTime = nowMillis - maxAgeMs
        while (snapshots.isNotEmpty() && snapshots.first().timestamp < minTime) {
            snapshots.removeAt(0)
        }
    }

    private fun loadHistory() {
        migrateLegacyHistoryFile()
        val file = Config.pathRallyHistory
        if (!Files.exists(file)) {
            return
        }

        try {
            val json = JsonParser.parseString(Files.readString(file)).asJsonObject
            val arr = json.getAsJsonArray("snapshots") ?: return
            val loaded: ArrayList<RallySnapshot> = arrayListOf()
            for (item in arr) {
                val obj = item.asJsonObject
                val ts = obj.get("time")?.asLong ?: continue
                val nationsObj = obj.getAsJsonObject("nations") ?: continue
                val counts = nationsObj.entrySet().associate { it.key to it.value.asInt }
                loaded.add(RallySnapshot(ts, counts))
            }

            val now = System.currentTimeMillis()
            synchronized(lock) {
                snapshots.clear()
                snapshots.addAll(loaded.sortedBy { it.timestamp })
                pruneUnsafe(now)
                lastSnapshotTime = snapshots.lastOrNull()?.timestamp ?: 0L
            }
        } catch (e: Exception) {
            Nodes.logger?.warning("Failed to load rally overview history: ${e.message}")
        }
    }

    private fun saveHistory() {
        val snapshotCopy = synchronized(lock) { snapshots.toList() }
        val root = JsonObject()
        val arr = JsonArray()
        for (snapshot in snapshotCopy) {
            val obj = JsonObject()
            obj.addProperty("time", snapshot.timestamp)
            val nations = JsonObject()
            snapshot.nationCounts.forEach { (nation, count) -> nations.addProperty(nation, count) }
            obj.add("nations", nations)
            arr.add(obj)
        }
        root.add("snapshots", arr)
        Files.createDirectories(Config.pathRallyHistory.parent)
        Files.writeString(Config.pathRallyHistory, root.toString())
    }

    private fun migrateLegacyHistoryFile() {
        if (Files.exists(Config.pathRallyHistory)) {
            return
        }

        val legacyFile = Paths.get(Config.pathPlugin, "rally_history.json").normalize()
        if (!Files.exists(legacyFile)) {
            return
        }

        runCatching {
            Files.createDirectories(Config.pathRallyHistory.parent)
            Files.copy(legacyFile, Config.pathRallyHistory)
            Files.deleteIfExists(legacyFile)
            Nodes.logger?.info("Migrated rally history to rally/rally_history.json")
        }.onFailure { e ->
            Nodes.logger?.warning("Failed to migrate legacy rally history: ${e.message}")
        }
    }

    private fun rallyPageHtml(): String = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>${Config.rallyOverviewPageTitle}</title>
          <style>
            :root {
              --bg: #071124;
              --bg2: #0a1a36;
              --panel: #0d2247cc;
              --line: #2a4f8d;
              --text: #eaf3ff;
              --muted: #9ec0ef;
              --accent: #66b8ff;
              --ok: #8ce2ff;
              --bar: linear-gradient(90deg, #3f89ff 0%, #53b4ff 45%, #84e0ff 100%);
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
            .wrap { max-width: 1280px; margin: 0 auto; }
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
            .hero h1 {
              margin: 0;
              font-size: 22px;
              letter-spacing: 0.3px;
            }
            .hero p {
              margin: 4px 0 0;
              color: var(--muted);
            }
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
              grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
              gap: 10px;
              align-items: end;
            }
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
            button:hover { filter: brightness(1.08); }
            .meta { color: var(--muted); margin-bottom: 10px; }
            table { width: 100%; border-collapse: collapse; }
            th, td { padding: 8px; border-bottom: 1px solid var(--line); text-align: left; }
            th { color: #d7e8ff; font-weight: 600; }
            .num { text-align: right; font-variant-numeric: tabular-nums; }
            .bar {
              height: 12px;
              border-radius: 999px;
              background: #081a35;
              overflow: hidden;
              min-width: 160px;
            }
            .fill {
              height: 100%;
              background: var(--bar);
            }
            .status { color: var(--ok); min-height: 20px; font-weight: 600; }
          </style>
        </head>
        <body>
          <div class="wrap">
            <div class="hero">
              <img src="/logo.png" alt="Siege Logo">
              <div>
                <h1>${Config.rallyOverviewPageTitle}</h1>
                <p>Rally analytics by date or exact time range.</p>
              </div>
            </div>
            <div class="card">
              <div class="controls">
                <div>
                  <label for="date">Exact Date</label>
                  <input id="date" type="date">
                </div>
                <div>
                  <button id="dateBtn">Load Date</button>
                </div>
                <div>
                  <label for="from">From</label>
                  <input id="from" type="datetime-local">
                </div>
                <div>
                  <label for="to">To</label>
                  <input id="to" type="datetime-local">
                </div>
                <div>
                  <button id="rangeBtn">Load Range</button>
                </div>
                <div>
                  <label for="lastHours">Quick Range</label>
                  <select id="lastHours">
                    <option value="1">Last 1h</option>
                    <option value="6">Last 6h</option>
                    <option value="12">Last 12h</option>
                    <option value="24">Last 24h</option>
                    <option value="72">Last 72h</option>
                    <option value="168">Last 7d</option>
                  </select>
                </div>
                <div>
                  <button id="quickBtn">Load Quick Range</button>
                </div>
                <div>
                  <label for="sortBy">Sort By</label>
                  <select id="sortBy">
                    <option value="avg">Average Rally</option>
                    <option value="peak">Peak Rally</option>
                    <option value="current">Current Rally</option>
                  </select>
                </div>
                <div>
                  <label for="autoRefresh">Auto Refresh</label>
                  <select id="autoRefresh">
                    <option value="0">Off</option>
                    <option value="10">10s</option>
                    <option value="30">30s</option>
                    <option value="60">60s</option>
                    <option value="120">120s</option>
                  </select>
                </div>
              </div>
            </div>

            <div class="card">
              <div class="meta" id="meta">Loading...</div>
              <div class="status" id="status"></div>
              <div style="overflow:auto;">
                <table>
                  <thead>
                    <tr>
                      <th>Nation</th>
                      <th>Avg Rally</th>
                      <th class="num">Peak Rally</th>
                      <th class="num">Current Rally</th>
                    </tr>
                  </thead>
                  <tbody id="rows"></tbody>
                </table>
              </div>
            </div>
          </div>

          <script>
            function esc(s) {
              return String(s).replace(/[&<>"']/g, function (ch) {
                return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[ch];
              });
            }

            function fmtTime(ms) {
              return new Date(ms).toLocaleString();
            }

            function buildRow(row, maxAvg) {
              var width = maxAvg > 0 ? Math.round((row.avgRally / maxAvg) * 100) : 0;
              return "<tr>" +
                "<td>" + esc(row.nation) + "</td>" +
                "<td><div style='display:flex;gap:8px;align-items:center;'><div class='bar'><div class='fill' style='width:" + width + "%'></div></div><span class='num'>" + Number(row.avgRally).toFixed(1) + "</span></div></td>" +
                "<td class='num'>" + row.peakRally + "</td>" +
                "<td class='num'>" + row.currentRally + "</td>" +
                "</tr>";
            }

            async function loadData(query) {
              var status = document.getElementById("status");
              status.textContent = "Loading...";
              try {
                var res = await fetch("/overview?" + query);
                if (!res.ok) {
                  throw new Error("HTTP " + res.status);
                }
                var data = await res.json();
                var nations = data.nations || [];
                var maxAvg = 0;
                for (var i = 0; i < nations.length; i++) {
                  if (nations[i].avgRally > maxAvg) maxAvg = nations[i].avgRally;
                }

                var html = "";
                for (var j = 0; j < nations.length; j++) {
                  html += buildRow(nations[j], maxAvg);
                }
                document.getElementById("rows").innerHTML = html || "<tr><td colspan='4'>No data in selected range</td></tr>";
                document.getElementById("meta").textContent =
                  "Range: " + fmtTime(data.from) + " -> " + fmtTime(data.to) +
                  " | Generated: " + fmtTime(data.generatedAt);
                status.textContent = "Loaded";
              } catch (e) {
                status.textContent = "Failed to load data: " + e.message;
              }
            }

            function withSort(baseQuery) {
              var sortBy = document.getElementById("sortBy").value || "avg";
              return baseQuery + "&sortBy=" + encodeURIComponent(sortBy);
            }

            document.getElementById("dateBtn").addEventListener("click", function () {
              var date = document.getElementById("date").value;
              if (!date) return;
              loadData(withSort("date=" + encodeURIComponent(date)));
            });

            document.getElementById("rangeBtn").addEventListener("click", function () {
              var from = document.getElementById("from").value;
              var to = document.getElementById("to").value;
              if (!from || !to) return;
              var fromIso = new Date(from).toISOString();
              var toIso = new Date(to).toISOString();
              loadData(withSort("from=" + encodeURIComponent(fromIso) + "&to=" + encodeURIComponent(toIso)));
            });

            document.getElementById("quickBtn").addEventListener("click", function () {
              var h = document.getElementById("lastHours").value || "24";
              loadData(withSort("lastHours=" + encodeURIComponent(h)));
            });

            (function initDefaults() {
              var defaultHours = "${Config.rallyOverviewDefaultLastHours}";
              var defaultSort = "${Config.rallyOverviewSortBy}";
              var defaultRefresh = "${Config.rallyOverviewAutoRefreshSeconds}";

              var lastHoursSel = document.getElementById("lastHours");
              var hasDefaultHours = false;
              for (var i = 0; i < lastHoursSel.options.length; i++) {
                if (lastHoursSel.options[i].value === defaultHours) {
                  hasDefaultHours = true;
                  break;
                }
              }
              if (!hasDefaultHours) {
                var opt = document.createElement("option");
                opt.value = defaultHours;
                opt.textContent = "Last " + defaultHours + "h";
                lastHoursSel.appendChild(opt);
              }
              lastHoursSel.value = defaultHours;

              document.getElementById("sortBy").value = defaultSort;

              var autoSel = document.getElementById("autoRefresh");
              var hasRefresh = false;
              for (var j = 0; j < autoSel.options.length; j++) {
                if (autoSel.options[j].value === defaultRefresh) {
                  hasRefresh = true;
                  break;
                }
              }
              if (!hasRefresh) {
                var autoOpt = document.createElement("option");
                autoOpt.value = defaultRefresh;
                autoOpt.textContent = defaultRefresh + "s";
                autoSel.appendChild(autoOpt);
              }
              autoSel.value = defaultRefresh;

              loadData(withSort("lastHours=" + encodeURIComponent(defaultHours)));

              var intervalMs = Number(defaultRefresh) * 1000;
              if (intervalMs > 0) {
                setInterval(function () {
                  var h = document.getElementById("lastHours").value || defaultHours;
                  loadData(withSort("lastHours=" + encodeURIComponent(h)));
                }, intervalMs);
              }
            })();
          </script>
        </body>
        </html>
    """.trimIndent()
}

private data class Quadruple<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
)
