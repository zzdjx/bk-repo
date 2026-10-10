FROM bkrepo/bkrepo-preview-base:0.0.3-jdk17

LABEL maintainer="Tencent BlueKing Devops"

# boot-driver 的 Playwright 1.55.0 对应 chromium_headless_shell-1187
ARG PLAYWRIGHT_CHROMIUM_REVISION=1187
ARG PLAYWRIGHT_DOWNLOAD_HOST=https://cdn.playwright.dev/dbazure/download/playwright

ENV BK_REPO_HOME=/data/workspace \
    BK_REPO_LOGS_DIR=/data/workspace/logs \
    BK_REPO_SERVICE_PREFIX=bkrepo- \
    BK_REPO_PROFILE=dev \
    HOME=/data/workspace \
    PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    PLAYWRIGHT_SKIP_BROWSER_GC=1 \
    PLAYWRIGHT_CHROMIUM_REVISION=${PLAYWRIGHT_CHROMIUM_REVISION} \
    PLAYWRIGHT_DOWNLOAD_HOST=${PLAYWRIGHT_DOWNLOAD_HOST}

# Chromium Headless Shell 运行时库（Playwright 1.55 / chromium 140）
RUN yum install -y --nogpgcheck --skip-unavailable \
        alsa-lib atk at-spi2-atk at-spi2-core \
        libdrm libXcomposite libXdamage libXfixes libXrandr \
        libxshmfence mesa-libgbm pango libwayland-client && \
    yum clean all

RUN set -eux; \
    browser_dir="${PLAYWRIGHT_BROWSERS_PATH}/chromium_headless_shell-${PLAYWRIGHT_CHROMIUM_REVISION}"; \
    mkdir -p "${browser_dir}" /tmp/pw-dl; \
    case "$(uname -m)" in \
      x86_64|amd64) archive="chromium-headless-shell-linux.zip" ;; \
      aarch64|arm64) archive="chromium-headless-shell-linux-arm64.zip" ;; \
      *) echo "unsupported arch: $(uname -m)" >&2; exit 1 ;; \
    esac; \
    printf '%s\n' \
      'import java.io.InputStream;' \
      'import java.net.URI;' \
      'import java.nio.file.Files;' \
      'import java.nio.file.Path;' \
      'import java.nio.file.StandardCopyOption;' \
      'import java.util.zip.ZipEntry;' \
      'import java.util.zip.ZipInputStream;' \
      'public class PwInstall {' \
      '  public static void main(String[] args) throws Exception {' \
      '    Path dir = Path.of(args[0]);' \
      '    Files.createDirectories(dir);' \
      '    Path zip = dir.resolve(".dl.zip");' \
      '    Exception last = null;' \
      '    String[] hosts = {' \
      '      System.getenv("PLAYWRIGHT_DOWNLOAD_HOST"),' \
      '      "https://cdn.playwright.dev/dbazure/download/playwright",' \
      '      "https://playwright.download.prss.microsoft.com/dbazure/download/playwright",' \
      '      "https://cdn.playwright.dev"' \
      '    };' \
      '    boolean ok = false;' \
      '    for (String host : hosts) {' \
      '      if (host == null || host.isBlank()) continue;' \
      '      String base = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;' \
      '      String url = base + "/" + args[1];' \
      '      try (InputStream in = URI.create(url).toURL().openStream()) {' \
      '        Files.copy(in, zip, StandardCopyOption.REPLACE_EXISTING);' \
      '      } catch (Exception e) { last = e; continue; }' \
      '      if (Files.size(zip) < 10000000L) { Files.deleteIfExists(zip); continue; }' \
      '      ok = true; break;' \
      '    }' \
      '    if (!ok) throw last != null ? last : new IllegalStateException("download failed");' \
      '    try (ZipInputStream stream = new ZipInputStream(Files.newInputStream(zip))) {' \
      '      for (ZipEntry entry; (entry = stream.getNextEntry()) != null; ) {' \
      '        Path out = dir.resolve(entry.getName()).normalize();' \
      '        if (!out.startsWith(dir)) throw new IllegalStateException(entry.getName());' \
      '        if (entry.isDirectory()) Files.createDirectories(out);' \
      '        else { Files.createDirectories(out.getParent()); Files.copy(stream, out, StandardCopyOption.REPLACE_EXISTING); }' \
      '      }' \
      '    }' \
      '    Files.deleteIfExists(zip);' \
      '  }' \
      '}' \
      > /tmp/pw-dl/PwInstall.java; \
    javac /tmp/pw-dl/PwInstall.java; \
    java -cp /tmp/pw-dl PwInstall "${browser_dir}" "builds/chromium/${PLAYWRIGHT_CHROMIUM_REVISION}/${archive}"; \
    chmod -R a+rX "${PLAYWRIGHT_BROWSERS_PATH}"; \
    chmod a+x "${browser_dir}/chrome-linux/headless_shell"; \
    if [ -f "${browser_dir}/chrome-linux/chrome-sandbox" ]; then \
        chown root:root "${browser_dir}/chrome-linux/chrome-sandbox"; \
        chmod 4755 "${browser_dir}/chrome-linux/chrome-sandbox"; \
    fi; \
    touch "${browser_dir}/INSTALLATION_COMPLETE" "${browser_dir}/DEPENDENCIES_VALIDATED"; \
    test -x "${browser_dir}/chrome-linux/headless_shell"; \
    if command -v ldd >/dev/null 2>&1; then \
        if ldd "${browser_dir}/chrome-linux/headless_shell" | grep -q 'not found'; then \
            ldd "${browser_dir}/chrome-linux/headless_shell"; \
            echo "Chromium Headless Shell is missing shared libraries" >&2; \
            exit 1; \
        fi; \
    fi; \
    rm -rf /tmp/pw-dl

COPY ./ /data/workspace/
# uid/gid 1001 与 chart fsGroup 一致。Chromium 拒绝在 root 下启用进程沙箱；
# chrome-sandbox 必须保持 root:root 4755，供非 root 进程走 setuid 沙箱。
RUN ln -snf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo 'Asia/Shanghai' > /etc/timezone && \
    groupadd --system --gid 1001 bkrepo && \
    useradd --system --uid 1001 --gid 1001 --home-dir /data/workspace --no-create-home --shell /sbin/nologin bkrepo && \
    mkdir -p /data/workspace/logs && \
    chown -R bkrepo:bkrepo /data/workspace && \
    chmod +x /data/workspace/startup.sh
WORKDIR /data/workspace
USER 1001:1001
CMD /data/workspace/startup.sh
