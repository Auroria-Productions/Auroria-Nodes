package phonon.nodes.overview

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.bukkit.Bukkit
import phonon.nodes.Config
import phonon.nodes.Nodes
import phonon.nodes.WebNewsItem
import phonon.nodes.market.MarketManager
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.concurrent.Executors

internal object WebOverviewService {
    private var server: HttpServer? = null

    @Volatile private var cachedOverviewJson: String? = null

    @Volatile private var cachedOverviewAt: Long = 0L

    /** Root folder that holds static web assets: plugins/nodes/web/ */
    private val webDir: File
        get() = Paths.get(Config.pathPlugin, "web").toFile()

    // MIME type lookup for common static file extensions
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
        if (!Config.webOverviewEnabled) {
            return
        }

        // Create web directory if it doesn't exist so admins know where to put files
        val dir = webDir
        if (!dir.exists()) {
            dir.mkdirs()
            Nodes.logger?.info("Created web assets directory at ${dir.path}")
        }

        try {
            val http = HttpServer.create(InetSocketAddress(Config.webOverviewBindAddress, Config.webOverviewWebPort), 0)
            // Mount static-file handler at root; the API and health endpoints take priority
            http.createContext("/health") { exchange -> handleHealth(exchange) }
            http.createContext("/api/overview") { exchange -> handleOverview(exchange) }
            http.createContext("/nodes/web/health") { exchange -> handleHealth(exchange) }
            http.createContext("/nodes/web/api/overview") { exchange -> handleOverview(exchange) }
            // Catch-all: serve from nodes/web/ folder (handles "/" and everything else)
            http.createContext("/") { exchange -> handleStaticFile(exchange) }
            http.executor = Executors.newFixedThreadPool(2)
            http.start()
            server = http
            Nodes.logger?.info("Web overview endpoint enabled on ${Config.webOverviewBindAddress}:${Config.webOverviewWebPort}")
            Nodes.logger?.info("Serving static web files from ${dir.absolutePath}")
        } catch (e: Exception) {
            Nodes.logger?.severe("Failed to start web overview endpoint: ${e.message}")
        }
    }

    fun stop() {
        server?.stop(0)
        server = null
        cachedOverviewJson = null
        cachedOverviewAt = 0L
    }

    /**
     * Serve a static file from the [webDir] folder.
     *
     * URL path mapping:
     *   /              -> nodes/web/index.html
     *   /nodes/web     -> nodes/web/index.html
     *   /nodes/web/    -> nodes/web/index.html
     *   /nodes/web/foo -> nodes/web/foo
     *   /foo.png       -> nodes/web/foo.png
     *   /some/path     -> nodes/web/some/path
     *
     * If the resolved file does not exist and the request is for "/" or "/nodes/web[/]",
     * the built-in [pageHtml] fallback is returned so the service still works with
     * an empty web directory.
     *
     * Path traversal is prevented: the resolved file must be inside [webDir].
     */
    private fun handleStaticFile(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            sendText(exchange, 405, "text/plain; charset=utf-8", "Method not allowed")
            return
        }

        val rawPath = exchange.requestURI.path ?: "/"

        // Normalise the request path relative to the web directory
        val relativePath = when {
            rawPath == "/" || rawPath == "/nodes/web" || rawPath == "/nodes/web/" -> "index.html"
            rawPath.startsWith("/nodes/web/") -> rawPath.removePrefix("/nodes/web/")
            else -> rawPath.removePrefix("/").ifEmpty { "index.html" }
        }

        // Resolve against the web directory and check for path-traversal
        val resolved = webDir.resolve(relativePath).canonicalFile
        if (!resolved.canonicalPath.startsWith(webDir.canonicalPath)) {
            sendText(exchange, 403, "text/plain; charset=utf-8", "Forbidden")
            return
        }

        // If it's a directory, try index.html inside it
        val target = if (resolved.isDirectory) File(resolved, "index.html") else resolved

        if (!target.exists() || !target.isFile) {
            // No file on disk – for the root path fall back to the built-in HTML page
            if (relativePath == "index.html") {
                sendText(exchange, 200, "text/html; charset=utf-8", pageHtml())
            } else {
                sendText(exchange, 404, "text/plain; charset=utf-8", "Not found")
            }
            return
        }

        val ext = target.extension.lowercase()
        val mime = MIME_TYPES[ext] ?: "application/octet-stream"
        val bytes = target.readBytes()
        sendBytes(exchange, 200, mime, bytes)
    }

    private fun handleHealth(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            sendJson(exchange, 405, """{"error":"method_not_allowed"}""")
            return
        }

        val body = JsonObject()
        body.addProperty("status", "ok")
        body.addProperty("time", System.currentTimeMillis())
        body.addProperty("newsCount", Config.webOverviewNews.size)
        body.addProperty("buttonCount", Config.webOverviewButtons.values.count { it.enabled })
        sendJson(exchange, 200, body.toString())
    }

    private fun handleOverview(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            sendJson(exchange, 405, """{"error":"method_not_allowed"}""")
            return
        }

        val now = System.currentTimeMillis()
        val cacheMs = Config.webOverviewResponseCacheMs.coerceAtLeast(0L)
        val cached = cachedOverviewJson
        if (cacheMs > 0L && cached != null && (now - cachedOverviewAt) < cacheMs) {
            sendJson(exchange, 200, cached)
            return
        }

        val buttonsJson = JsonArray()
        Config.webOverviewButtons.values
            .filter { it.enabled }
            .forEach { button ->
                buttonsJson.add(
                    JsonObject().apply {
                        addProperty("id", button.id)
                        addProperty("label", button.label)
                        addProperty("description", button.description)
                        addProperty("url", button.url)
                    },
                )
            }

        val featuresJson = JsonArray()
        for (feature in Config.webOverviewFeatures) {
            featuresJson.add(
                JsonObject().apply {
                    addProperty("title", feature.title)
                    addProperty("description", feature.description)
                },
            )
        }

        val tipsJson = JsonArray()
        for (tip in Config.webOverviewTips) {
            tipsJson.add(tip)
        }

        val newsJson = JsonArray()
        Config.webOverviewNews
            .sortedWith(compareByDescending<WebNewsItem> { it.pinned }.thenByDescending { it.date })
            .forEach { news ->
                newsJson.add(
                    JsonObject().apply {
                        addProperty("date", news.date)
                        addProperty("title", news.title)
                        addProperty("content", news.content)
                        addProperty("link", news.link)
                        addProperty("pinned", news.pinned)
                    },
                )
            }

        val stats = JsonObject().apply {
            addProperty("onlinePlayers", Bukkit.getOnlinePlayers().size)
            addProperty("towns", Nodes.getTownCount())
            addProperty("nations", Nodes.getNationCount())
            addProperty("territories", Nodes.getTerritoryCount())
            addProperty("activeWars", if (Nodes.war.enabled) 1 else 0)
            addProperty("marketListings", runCatching { MarketManager.getAllListingsSnapshot().values.sumOf { it.size } }.getOrDefault(0))
        }

        val design = JsonObject().apply {
            addProperty("pageTitle", Config.webOverviewPageTitle)
            addProperty("serverName", Config.webOverviewServerName)
            addProperty("subtitle", Config.webOverviewSubtitle)
            addProperty("footerText", Config.webOverviewFooterText)
            addProperty("heroBackgroundUrl", Config.webOverviewHeroBackgroundUrl)
            addProperty("mainImageUrl", Config.webOverviewMainImageUrl)
            addProperty("primaryColor", Config.webOverviewPrimaryColor)
            addProperty("secondaryColor", Config.webOverviewSecondaryColor)
            addProperty("accentColor", Config.webOverviewAccentColor)
            addProperty("backgroundTop", Config.webOverviewBackgroundTop)
            addProperty("backgroundBottom", Config.webOverviewBackgroundBottom)
        }

        val payload = JsonObject().apply {
            addProperty("generatedAt", now)
            add("design", design)
            add("stats", stats)
            add("buttons", buttonsJson)
            add("features", featuresJson)
            add("tips", tipsJson)
            add("news", newsJson)
        }

        val json = payload.toString()
        if (cacheMs > 0L) {
            cachedOverviewJson = json
            cachedOverviewAt = now
        }
        sendJson(exchange, 200, json)
    }

    // -------------------------------------------------------------------------
    // Transport helpers
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Built-in fallback page (used when nodes/web/index.html does not exist)
    // -------------------------------------------------------------------------

    private fun pageHtml(): String = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>${Config.webOverviewPageTitle}</title>
          <style>
            :root {
              --primary: ${Config.webOverviewPrimaryColor};
              --secondary: ${Config.webOverviewSecondaryColor};
              --accent: ${Config.webOverviewAccentColor};
              --bg-top: ${Config.webOverviewBackgroundTop};
              --bg-bottom: ${Config.webOverviewBackgroundBottom};
              --panel: #0f1219de;
              --line: #252a35;
              --text: #f0f2f6;
              --muted: #a1a7b3;
            }
            * { box-sizing: border-box; }
            body {
              margin: 0;
              padding: 0;
              color: var(--text);
              font: 15px/1.5 "Segoe UI", Tahoma, Arial, sans-serif;
              background: linear-gradient(180deg, var(--bg-top), var(--bg-bottom));
            }
            .container {
              width: min(1540px, calc(100% - 34px));
              margin: 18px auto 26px;
            }
            .hero-shell {
              position: relative;
              border: 1px solid #2e3440;
              border-radius: 8px;
              overflow: hidden;
              background: #070b13;
              min-height: 430px;
            }
            .hero-bg {
              position: absolute;
              inset: 0;
              background:
                radial-gradient(circle at 15% 20%, #2a66b433 0%, transparent 42%),
                radial-gradient(circle at 80% 10%, #ff7b0026 0%, transparent 35%),
                linear-gradient(180deg, #08172f, #040912);
              background-size: cover;
              background-position: center;
              filter: blur(0px) brightness(.56);
              transform: scale(1.04);
            }
            .hero-overlay {
              position: absolute;
              inset: 0;
              background:
                linear-gradient(180deg, #030914d0 0%, #060b13c0 42%, #060b12f5 100%);
            }
            .hero-content { position: relative; z-index: 1; }
            .top-nav {
              display: flex;
              justify-content: center;
              gap: 12px;
              padding: 16px 16px 0;
              flex-wrap: wrap;
            }
            .nav-btn {
              text-decoration: none;
              color: #d4d8e0;
              border-radius: 6px;
              padding: 10px 18px;
              font-weight: 700;
              background: transparent;
              border: 1px solid transparent;
            }
            .nav-btn:hover { border-color: #3b4250; background: #141a2590; color: #fff; }
            .nav-btn.primary {
              background: linear-gradient(180deg, #ff8a00, #ff6e00);
              border-color: #ff8a00;
              color: #fff;
              box-shadow: 0 0 0 2px #ff7a001f;
            }
            .hero-center {
              padding: 86px 34px 18px;
              display: grid;
              grid-template-columns: 1fr auto 1fr;
              align-items: center;
              gap: 26px;
            }
            .hero-logo-wrap { text-align: center; }
            .hero-logo {
              max-width: min(420px, 58vw);
              width: 100%;
              height: auto;
              filter: drop-shadow(0 10px 28px rgba(0,0,0,.48));
            }
            .pill {
              display: inline-flex;
              align-items: center;
              gap: 10px;
              justify-content: center;
              color: #ff8a00;
              font-weight: 700;
              text-align: center;
            }
            .pill .sub { color: #d9dde4; font-size: 12px; font-weight: 600; }
            .welcome-strip {
              margin: 12px auto 0;
              width: calc(100% - 52px);
              display: grid;
              grid-template-columns: 1fr 320px;
              gap: 18px;
              align-items: stretch;
              padding-bottom: 18px;
            }
            .welcome-card, .store-card {
              border-radius: 8px;
              border: 1px solid #2f343f;
              background: #11151ddd;
              padding: 16px 18px;
            }
            .welcome-top { display: flex; justify-content: space-between; gap: 10px; align-items: center; }
            .welcome-title { color: #ff7a00; font-size: 40px; font-weight: 800; margin: 0; line-height: 1; }
            .welcome-sub { color: #c8ced8; margin-top: 6px; font-size: 16px; }
            .small-btn {
              text-decoration: none;
              color: #fff;
              border-radius: 6px;
              padding: 10px 15px;
              font-weight: 700;
              background: #ff7a00;
              display: inline-block;
            }
            .small-btn.ghost { background: #353c48; }
            .store-card {
              border-color: #ff7a00;
              background: linear-gradient(135deg, #cc5f00, #ad4e00);
              color: #fff;
              display: flex;
              flex-direction: column;
              justify-content: center;
            }
            .store-card .t { font-size: 44px; font-style: italic; font-weight: 800; margin: 0; line-height: 1; }
            .store-card .d { margin-top: 10px; opacity: .95; font-size: 16px; }

            .main-row {
              margin-top: 18px;
              display: grid;
              grid-template-columns: 1.5fr 1fr;
              gap: 18px;
            }
            .panel {
              border: 1px solid #282d39;
              border-radius: 8px;
              background: #10141ccc;
              overflow: hidden;
            }
            .stats-panel-content { padding: 26px; }
            .stats-title { margin: 0 0 14px; color: #ff7a00; font-size: 38px; line-height: 1.05; }
            .stats-grid {
              display: grid;
              grid-template-columns: repeat(2, minmax(0, 1fr));
              gap: 12px;
            }
            .stat-card {
              border: 1px solid #2d3340;
              border-radius: 8px;
              background: #141925;
              padding: 14px;
              min-height: 110px;
            }
            .stat-label { color: #aab3c2; font-size: 13px; font-weight: 700; text-transform: uppercase; letter-spacing: .04em; }
            .stat-value { margin-top: 7px; color: #ffffff; font-size: 34px; font-weight: 800; line-height: 1; }
            .stat-note { margin-top: 6px; color: #8f98a8; font-size: 12px; }
            .right-content { padding: 28px; }
            .right-content h2 { margin: 0 0 12px; color: #ff7a00; font-size: 52px; line-height: 1.04; }
            .right-content p { color: #c4cad4; margin: 0 0 18px; font-size: 22px; line-height: 1.45; }
            .feature-list, .tips { margin: 0; padding-left: 18px; color: #ccd2db; }
            .feature-list li, .tips li { margin: 6px 0; }

            .news-panel {
              margin-top: 18px;
              border: 1px solid #282d39;
              border-radius: 8px;
              background: #10141ccc;
              padding: 22px;
            }
            .news-title { margin: 0 0 14px; font-size: 28px; color: #ff7a00; }
            .news-grid {
              display: grid;
              grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
              gap: 14px;
            }
            .news-item {
              border: 1px solid #2d3340;
              border-radius: 7px;
              background: #141925;
              padding: 14px;
            }
            .news-item .head { display: flex; justify-content: space-between; gap: 8px; margin-bottom: 4px; }
            .news-item .h { font-weight: 700; }
            .news-item .date { color: var(--muted); font-size: 12px; }
            .pin { display: inline-block; margin-left: 5px; padding: 1px 6px; border-radius: 999px; border: 1px solid #7b4f25; background: #5a360d; color: #ffd2a6; font-size: 11px; }
            .meta { margin-top: 10px; color: #9aa2ad; font-size: 12px; border-top: 1px solid #282d39; padding-top: 8px; }

            @media (max-width: 1100px) {
              .hero-center { grid-template-columns: 1fr; text-align: center; padding-top: 42px; }
              .hero-logo { max-width: min(360px, 75vw); }
              .welcome-strip { grid-template-columns: 1fr; }
              .main-row { grid-template-columns: 1fr; }
              .stats-grid { grid-template-columns: 1fr; }
              .right-content h2 { font-size: 32px; }
              .right-content p { font-size: 17px; }
            }
          </style>
        </head>
        <body>
          <div class="container">
            <section class="hero-shell">
              <div class="hero-bg" id="heroBg"></div>
              <div class="hero-overlay"></div>
              <div class="hero-content">
                <nav class="top-nav" id="topNav"></nav>
                <div class="hero-center">
                  <div class="pill">
                    <div>
                      SERVER STATUS
                      <div class="sub" id="serverStatusSub">Loading...</div>
                    </div>
                  </div>
                  <div class="hero-logo-wrap">
                    <img class="hero-logo" src="/logo.png" alt="Siege Logo">
                  </div>
                  <div class="pill">
                    <div>
                      PLAYERS ONLINE
                      <div class="sub" id="playersOnlineSub">Loading...</div>
                    </div>
                  </div>
                </div>
                <div class="welcome-strip">
                  <div class="welcome-card">
                    <div class="welcome-top">
                      <div>
                        <h1 class="welcome-title" id="serverName">${Config.webOverviewServerName}</h1>
                        <div class="welcome-sub" id="subtitle">${Config.webOverviewSubtitle}</div>
                      </div>
                      <div>
                        <a class="small-btn" id="loginBtn" href="#" target="_blank" rel="noopener">Open</a>
                        <a class="small-btn ghost" id="registerBtn" href="#" target="_blank" rel="noopener">Second Link</a>
                      </div>
                    </div>
                  </div>
                  <div class="store-card" id="storeCard">
                    <h3 class="t">STORE</h3>
                    <div class="d">Shop now</div>
                  </div>
                </div>
              </div>
            </section>

            <section class="main-row">
              <article class="panel">
                <div class="stats-panel-content">
                  <h2 class="stats-title">Live Server Stats</h2>
                  <div class="stats-grid">
                    <div class="stat-card">
                      <div class="stat-label">Online Players</div>
                      <div class="stat-value" id="statOnlinePlayers">0</div>
                      <div class="stat-note">Currently online</div>
                    </div>
                    <div class="stat-card">
                      <div class="stat-label">Towns</div>
                      <div class="stat-value" id="statTowns">0</div>
                      <div class="stat-note">Registered towns</div>
                    </div>
                    <div class="stat-card">
                      <div class="stat-label">Nations</div>
                      <div class="stat-value" id="statNations">0</div>
                      <div class="stat-note">Active nations</div>
                    </div>
                    <div class="stat-card">
                      <div class="stat-label">Territories</div>
                      <div class="stat-value" id="statTerritories">0</div>
                      <div class="stat-note">Claimed territories</div>
                    </div>
                    <div class="stat-card">
                      <div class="stat-label">Active Wars</div>
                      <div class="stat-value" id="statActiveWars">0</div>
                      <div class="stat-note">Ongoing conflicts</div>
                    </div>
                    <div class="stat-card">
                      <div class="stat-label">Market Listings</div>
                      <div class="stat-value" id="statMarketListings">0</div>
                      <div class="stat-note">Items on the market</div>
                    </div>
                  </div>
                </div>
              </article>
              <article class="panel">
                <div class="right-content">
                  <h2 id="mainTitle">Welcome</h2>
                  <p id="mainDescription">Loading...</p>
                  <ul class="feature-list" id="features"></ul>
                  <h3 style="margin:14px 0 8px; color:#ff7a00;">Tips</h3>
                  <ul class="tips" id="tips"></ul>
                </div>
              </article>
            </section>

            <section class="news-panel">
              <h3 class="news-title">Latest News</h3>
              <div class="news-grid" id="news"></div>
              <div class="meta" id="footerText">${Config.webOverviewFooterText}</div>
            </section>
          </div>

          <script>
            function esc(s){ return String(s).replace(/[&<>"']/g, function(ch){ return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[ch]; }); }

            function render(data){
              var design = data.design || {};
              var buttons = data.buttons || [];
              var stats = data.stats || {};

              document.title = design.pageTitle || document.title;
              document.getElementById("serverName").textContent = design.serverName || "";
              document.getElementById("subtitle").textContent = design.subtitle || "";
              document.getElementById("mainTitle").textContent = "Welcome to " + (design.serverName || "Nodes");
              document.getElementById("mainDescription").textContent = design.subtitle || "";
              document.getElementById("footerText").textContent = design.footerText || "";

              var hero = document.getElementById("heroBg");
              if (design.heroBackgroundUrl) {
                hero.style.backgroundImage = "linear-gradient(180deg, rgba(4,8,15,.15), rgba(4,8,15,.55)), url('" + esc(design.heroBackgroundUrl) + "')";
              }

              document.getElementById("serverStatusSub").textContent = (stats.activeWars > 0 ? "WAR ACTIVE" : "ONLINE");
              document.getElementById("playersOnlineSub").textContent = String(stats.onlinePlayers || 0);
              document.getElementById("statOnlinePlayers").textContent = String(stats.onlinePlayers || 0);
              document.getElementById("statTowns").textContent = String(stats.towns || 0);
              document.getElementById("statNations").textContent = String(stats.nations || 0);
              document.getElementById("statTerritories").textContent = String(stats.territories || 0);
              document.getElementById("statActiveWars").textContent = String(stats.activeWars || 0);
              document.getElementById("statMarketListings").textContent = String(stats.marketListings || 0);

              document.getElementById("topNav").innerHTML = buttons.map(function(b, i){
                var cls = i === 1 ? "nav-btn primary" : "nav-btn";
                return "<a class='" + cls + "' href='" + esc(b.url || "#") + "' target='_blank' rel='noopener'>" + esc(b.label || "Link") + "</a>";
              }).join("");

              var primary = buttons[0] || null;
              var secondary = buttons[1] || null;
              if (primary) {
                document.getElementById("loginBtn").href = primary.url || "#";
                document.getElementById("loginBtn").textContent = primary.label || "Open";
              }
              if (secondary) {
                document.getElementById("registerBtn").href = secondary.url || "#";
                document.getElementById("registerBtn").textContent = secondary.label || "Second Link";
              }
              var storeBtn = buttons.find(function(b){ return (b.id || "").toLowerCase() === "shop"; }) || secondary || primary;
              if (storeBtn) {
                document.getElementById("storeCard").onclick = function(){ window.open(storeBtn.url || "#", "_blank"); };
                document.getElementById("storeCard").style.cursor = "pointer";
              }

              var features = data.features || [];
              document.getElementById("features").innerHTML = features.map(function(f){
                return "<li><strong>" + esc(f.title || "") + ":</strong> " + esc(f.description || "") + "</li>";
              }).join("");

              var tips = data.tips || [];
              document.getElementById("tips").innerHTML = tips.map(function(t){
                return "<li>" + esc(t) + "</li>";
              }).join("");

              var news = data.news || [];
              document.getElementById("news").innerHTML = news.map(function(n){
                var pin = n.pinned ? "<span class='pin'>PINNED</span>" : "";
                var content = esc(n.content || "");
                if (n.link) {
                  content += " <a href='" + esc(n.link) + "' target='_blank' rel='noopener' style='color:#ffae6e;'>Read</a>";
                }
                return "<article class='news-item'><div class='head'><div class='h'>" + esc(n.title || "") + pin + "</div><div class='date'>" + esc(n.date || "") + "</div></div><div>" + content + "</div></article>";
              }).join("");
            }

            async function load() {
              try {
                var res = await fetch("/api/overview");
                if (!res.ok) throw new Error("HTTP " + res.status);
                var data = await res.json();
                render(data);
              } catch (e) {
                document.getElementById("serverStatusSub").textContent = "ERROR";
                document.getElementById("playersOnlineSub").textContent = "0";
              }
            }

            load();
            setInterval(load, 30000);
          </script>
        </body>
        </html>
    """.trimIndent()
}
