package com.tencent.bkrepo.preview.service.share

import org.springframework.web.util.HtmlUtils
import java.util.Base64

/**
 * 作品分享短链 `/a/{shareId}`、`/share/{shortShareId}` 的 403/404 HTML。
 * 布局与 BKWorkspace 共享邀请页对齐，文案按分享场景替换。
 */
object ArtifactShareErrorPage {

    fun forbidden(createdBy: String): String {
        val contact = HtmlUtils.htmlEscape(createdBy.trim().ifBlank { FALLBACK_CONTACT }, Charsets.UTF_8.name())
        return render(
            title = FORBIDDEN_TITLE,
            description = "你无权限访问此作品，如需使用请联系 $contact",
            showRetry = true,
        )
    }

    fun notFound(): String {
        return render(
            title = NOT_FOUND_TITLE,
            description = NOT_FOUND_DESCRIPTION,
            showRetry = false,
        )
    }

    private fun render(title: String, description: String, showRetry: Boolean): String {
        val retryBlock = if (showRetry) {
            """
            <div class="actions">
            <button type="button" onclick="location.reload()">
            $RETRY_ICON
            $RETRY_LABEL
            </button>
            </div>
            """.trimIndent()
        } else {
            ""
        }
        return """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="UTF-8">
            <link rel="icon" type="image/png" href="$logoSrc">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>$title</title>
            <style>
            :root{
              --background:#fff;
              --muted:#f4f4f5;
              --muted-foreground:#71717a;
              --border:#e4e4e7;
              --primary:#18181b;
              --primary-foreground:#fafafa;
              --brand:#3b82f6
            }
            *{box-sizing:border-box}
            html,body{margin:0}
            body{
              font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;
              color:#09090b;background:var(--background)
            }
            .page{
              position:relative;min-height:100vh;overflow:hidden;
              background:linear-gradient(to bottom,var(--background),var(--background),rgba(244,244,245,.4))
            }
            .glow{position:absolute;inset:0;pointer-events:none}
            .glow-a{
              position:absolute;top:-8rem;left:50%;height:480px;width:480px;margin-left:-240px;
              border-radius:9999px;background:rgba(59,130,246,.10);filter:blur(64px)
            }
            .glow-b{
              position:absolute;right:0;bottom:0;height:16rem;width:16rem;
              border-radius:9999px;background:rgba(59,130,246,.10);filter:blur(64px)
            }
            .header{
              position:relative;height:4rem;border-bottom:1px solid rgba(228,228,231,.5);
              background:rgba(255,255,255,.7);backdrop-filter:blur(8px)
            }
            .header-inner,.main{
              width:100%;max-width:42rem;margin:0 auto;padding:0 1.5rem
            }
            .header-inner{height:4rem;display:flex;align-items:center}
            .brand{
              display:flex;align-items:center;gap:.5rem;color:inherit;text-decoration:none
            }
            .brand img{height:2rem;width:2rem;border-radius:.375rem}
            .brand span{font-weight:600;letter-spacing:-.025em}
            .main{position:relative;padding:4rem 1.5rem 6rem;text-align:center}
            @media (min-width:640px){.main{padding-top:6rem;padding-bottom:6rem}}
            .icon-wrap{
              display:inline-flex;margin:0 auto 1rem;padding:1rem;border-radius:1rem;
              background:linear-gradient(to bottom right,rgba(59,130,246,.15),rgba(59,130,246,.15))
            }
            .icon-wrap svg{width:2.5rem;height:2.5rem;color:var(--brand)}
            h1{margin:0;font-size:1.875rem;line-height:2.25rem;font-weight:700;letter-spacing:-.025em}
            @media (min-width:640px){h1{font-size:2.25rem;line-height:2.5rem}}
            .desc{
              margin:0.75rem auto 0;max-width:28rem;font-size:1rem;line-height:1.75;
              color:var(--muted-foreground)
            }
            .actions{margin:2.5rem auto 0;max-width:20rem;display:flex;flex-direction:column;gap:.75rem}
            button{
              display:inline-flex;align-items:center;justify-content:center;gap:.5rem;height:2.5rem;
              padding:0 1rem;border:0;border-radius:.375rem;cursor:pointer;
              background:var(--primary);color:var(--primary-foreground);font-size:.875rem;font-weight:500
            }
            button:hover{opacity:.9}
            button svg{width:1rem;height:1rem;flex-shrink:0}
            .footer{
              margin:4rem 0 0;text-align:center;font-size:.75rem;color:var(--muted-foreground)
            }
            </style>
            </head>
            <body>
            <div class="page">
            <div class="glow" aria-hidden="true">
            <div class="glow-a"></div>
            <div class="glow-b"></div>
            </div>
            <header class="header">
            <div class="header-inner">
            <a class="brand" href="/">
            <img src="$logoSrc" alt="BKWorkspace">
            <span>BKWorkspace</span>
            </a>
            </div>
            </header>
            <main class="main">
            <div class="icon-wrap">$SHARE_ICON</div>
            <h1>$title</h1>
            <p class="desc">$description</p>
            $retryBlock
            <p class="footer">$FOOTER</p>
            </main>
            </div>
            </body>
            </html>
        """.trimIndent()
    }

    private const val FORBIDDEN_TITLE = "无权限访问"
    private const val NOT_FOUND_TITLE = "无分享作品"
    private const val NOT_FOUND_DESCRIPTION = "分享作品不存在或已删除"
    private const val FALLBACK_CONTACT = "分享者"
    private const val RETRY_LABEL = "我已开通权限，点此重试"
    private const val LOGO_RESOURCE = "/share/bkw.png"
    private const val FOOTER = "BKWorkspace · 云桌面 · 云端 AI Agent 军团"
    private val SHARE_ICON = """
        <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"
            fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"
            stroke-linejoin="round" class="lucide lucide-share2" aria-hidden="true">
            <circle cx="18" cy="5" r="3"></circle>
            <circle cx="6" cy="12" r="3"></circle>
            <circle cx="18" cy="19" r="3"></circle>
            <line x1="8.59" x2="15.42" y1="13.51" y2="17.49"></line>
            <line x1="15.41" x2="8.59" y1="6.51" y2="10.49"></line>
        </svg>
        """.trimIndent()
    private val RETRY_ICON = """
        <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"
            fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"
            stroke-linejoin="round" class="lucide lucide-refresh-cw" aria-hidden="true">
            <path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"></path>
            <path d="M21 3v5h-5"></path>
            <path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"></path>
            <path d="M8 16H3v5"></path>
        </svg>
        """.trimIndent()

    private val logoSrc: String by lazy { loadLogoDataUri() }

    private fun loadLogoDataUri(): String {
        val stream = ArtifactShareErrorPage::class.java.getResourceAsStream(LOGO_RESOURCE) ?: return ""
        return stream.use { input ->
            "data:image/png;base64," + Base64.getEncoder().encodeToString(input.readBytes())
        }
    }
}
