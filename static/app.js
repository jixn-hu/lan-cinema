const { createApp } = Vue;
createApp({
  data() {
    return {
      native: !!window.LanCinema,
      view: "library",
      self: {},
      sourceInfo: null,
      source: null,
      devices: [],
      manuals: JSON.parse(localStorage.getItem("lan-peers") || "[]"),
      dirs: [],
      localDirs: [],
      files: [],
      search: "",
      type: "all",
      sortKey: "mtime_desc",
      currentDir: "",
      loading: false,
      error: "",
      progressMap: {},
      coverFailed: {},
      settings: false,
      showDirManager: false,
      newDir: "",
      dirBusy: false,
      dirError: "",
      accessUrls: [],
      manualURL: "",
      connecting: false,
      discoveryError: "",
      toast: "",
      cache: {},
      cacheLimit: 20,
      downloads: [],
      favorites: JSON.parse(localStorage.getItem("lan-favorites") || "[]"),
      favoriteSearch: "",
      openingFavorite: "",
      downloadSort: localStorage.getItem("lan-download-sort") || "added_desc",
      refreshInterval: Number(localStorage.getItem("lan-refresh-seconds")) || 0,
      lastRefresh: 0,
      libraryRevision: "",
      checkingChanges: false,
      deleteTarget: null,
      deleting: false,
      playQueue: [],
      queueSource: null,
      queueProgress: {},
      playlistOpen: false,
      tabs: [
        { value: "all", label: "全部" },
        { value: "video", label: "视频" },
        { value: "image", label: "图片" },
        { value: "other", label: "其他" },
      ],
      player: {
        file: null,
        rate: 1,
        zoom: 1,
        seekPreview: null,
        x: 0,
        y: 0,
        controls: true,
        playing: false,
        current: 0,
        duration: 0,
        buffered: 0,
        waiting: false,
        fit: "contain",
        expanded: false,
        boost: false,
      },
      lightbox: "",
      boostRate: 2,
      loadSeq: 0,
      saveTimer: null,
      pollTimer: null,
      debounceTimer: null,
      holdTimer: null,
      toastTimer: null,
      pointerPoints: {},
      pinchDistance: 0,
      gestureMoved: false,
      gestureMulti: false,
      gestureAxis: "",
      gestureStart: 0,
      downloadsTimer: null,
      controlsTimer: null,
      resizeObserver: null,
    };
  },
  computed: {
    filteredFavorites() {
      const query = this.favoriteSearch.trim().toLocaleLowerCase();
      return this.favorites.filter((b) =>
        (b.file.name + " " + b.deviceName).toLocaleLowerCase().includes(query),
      );
    },
    compatibilityHint() {
      if (this.sourceInfo && !this.sourceInfo.features?.includes("delete"))
        return this.upgradeHint(this.sourceInfo);
      return "";
    },
    sortedDownloads() {
      const [key, direction] = this.downloadSort.split("_");
      const value = (d) =>
        key === "progress"
          ? d.total > 0
            ? d.bytes / d.total
            : -1
          : key === "size"
            ? d.total
            : key === "status"
              ? d.complete
                ? 2
                : d.temporary
                  ? 1
                  : 0
              : d[key] || 0;
      return [...this.downloads].sort((a, b) => {
        const comparison =
          key === "name"
            ? a.name.localeCompare(b.name, "zh-CN", { numeric: true })
            : value(a) - value(b);
        return (
          comparison * (direction === "asc" ? 1 : -1) ||
          a.id.localeCompare(b.id)
        );
      });
    },
    episodeIndex() {
      return this.playQueue.findIndex((f) => f.id === this.player.file?.id);
    },
    lockDescription() {
      return this.cache.lockEnabled
        ? "已启用。重新打开或离开 App 超过 30 秒后需要解锁。"
        : "设置密码，保护这台手机上的 App。";
    },
    totalSize() {
      return this.files.reduce((n, f) => n + f.size, 0);
    },
  },
  methods: {
    notify(s) {
      this.toast = s;
      clearTimeout(this.toastTimer);
      this.toastTimer = setTimeout(() => (this.toast = ""), 4000);
    },
    apiURL(path, source = this.source) {
      const u = new URL(source?.url || location.origin);
      const token =
        u.searchParams.get("t") ||
        (!source ? new URL(location.href).searchParams.get("t") : null);
      const result = new URL(path, u.origin);
      if (token) result.searchParams.set("t", token);
      return result.href;
    },
    upgradeHint(info) {
      return info?.platform === "android"
        ? "源设备上的 App 版本过旧，不支持删除。请先更新源 App 并重新打开。"
        : "电脑当前运行的共享服务版本过旧，不支持删除。请更新电脑端，并关闭旧服务窗口后重新运行 run.cmd。";
    },
    async connectionFailure(path, options, source, address) {
      if (
        options.method &&
        !["GET", "HEAD"].includes(options.method.toUpperCase())
      ) {
        const probe = new AbortController();
        const timer = setTimeout(() => probe.abort(), 3000);
        try {
          const response = await fetch(this.apiURL("/api/device", source), {
            signal: probe.signal,
          });
          if (response.ok) {
            const info = await response.json();
            if (
              options.method.toUpperCase() === "DELETE" &&
              !info.features?.includes("delete")
            )
              return this.upgradeHint(info);
            return `源设备 ${address} 可以访问，但操作请求被拒绝或响应中断。请更新并重新启动源设备的共享服务后重试。`;
          }
          if (response.status === 403)
            return "源设备拒绝访问，请检查连接地址中的访问令牌";
        } catch {
        } finally {
          clearTimeout(timer);
        }
      }
      return `无法连接源设备 ${address}。请确认共享服务已启动、设备仍连接同一局域网，并检查电脑防火墙。`;
    },
    async request(path, options = {}, source = this.source) {
      const url = this.apiURL(path, source);
      const controller = new AbortController();
      const timeout = setTimeout(
        () => controller.abort(),
        path.startsWith("/api/files") ? 60000 : 12000,
      );
      let received = false;
      try {
        const response = await fetch(url, {
          ...options,
          signal: controller.signal,
        });
        received = true;
        if (!response.ok) {
          let info;
          try {
            info = await response.json();
          } catch {}
          const detail = Array.isArray(info?.detail)
            ? info.detail.map((x) => x.msg).join("；")
            : info?.detail;
          const error = Error(
            typeof detail === "string" &&
              !["Not Found", "Method Not Allowed"].includes(detail)
              ? detail
              : response.status === 403
                ? "源设备拒绝操作，请检查访问令牌或文件权限"
                : response.status === 404
                  ? "文件或接口不存在，请刷新列表；旧版共享服务请更新并重启"
                  : response.status === 405
                    ? "源设备不支持该操作，请更新并重启共享服务"
                    : response.status >= 500
                      ? "源设备处理请求失败，请查看源设备的服务日志后重试"
                      : `操作失败（${response.status}），请刷新后重试`,
          );
          error.status = response.status;
          throw error;
        }
        const data = await response.json();
        if (Array.isArray(data))
          data.libraryRevision = response.headers.get("X-Library-Revision");
        return data;
      } catch (error) {
        const address = new URL(url).origin;
        if (
          controller.signal.aborted &&
          options.method &&
          !["GET", "HEAD"].includes(options.method.toUpperCase())
        )
          throw Error(
            (await this.connectionFailure(path, options, source, address)) +
              " 请求超时后请先刷新列表，确认操作结果。",
          );
        if (controller.signal.aborted)
          throw Error(
            `连接 ${address} 超时。请确认电脑服务仍在运行、地址属于当前 Wi-Fi，并允许防火墙的局域网访问`,
          );
        if (error instanceof TypeError && !received)
          throw Error(
            await this.connectionFailure(path, options, source, address),
          );
        if (error instanceof SyntaxError)
          throw Error(
            "源设备返回了无法识别的数据，请检查访问令牌或更新并重启共享服务",
          );
        throw error;
      } finally {
        clearTimeout(timeout);
      }
    },
    fmtSize(n) {
      if (!Number.isFinite(n) || n < 0) return "—";
      if (n < 1024) return n + " B";
      const u = ["KB", "MB", "GB", "TB"];
      let i = -1;
      do {
        n /= 1024;
        i++;
      } while (n >= 1024 && i < u.length - 1);
      return n.toFixed(1) + " " + u[i];
    },
    fmtDate(t) {
      return t
        ? new Date(t * 1000).toLocaleDateString("zh-CN", {
            month: "2-digit",
            day: "2-digit",
          })
        : "";
    },
    extension(n) {
      return n.split(".").pop().slice(0, 8).toUpperCase();
    },
    async discover() {
      try {
        const data = await this.request("/api/devices", {}, null);
        this.devices = [...(data.devices || [])];
        for (const d of this.manuals)
          if (!this.devices.some((x) => x.id === d.id)) this.devices.push(d);
        this.discoveryError = data.error || "";
      } catch (e) {
        this.discoveryError = e.message;
      }
    },
    async selectDevice(d) {
      this.closePlayer();
      this.source = d;
      this.sourceInfo = null;
      this.request("/api/device", {}, d)
        .then((info) => {
          if (this.source === d) this.sourceInfo = info;
        })
        .catch(() => {});
      this.libraryRevision = "";
      this.view = "library";
      this.currentDir = "";
      this.coverFailed = {};
      this.dirs = [];
      this.request("/api/dirs")
        .then((d) => (this.dirs = d.dirs || []))
        .catch(() => {});
      await this.load();
    },
    async load(refresh = false) {
      refresh = refresh === true;
      const seq = ++this.loadSeq;
      const source = this.source;
      this.loading = true;
      this.error = "";
      const [sort, order] = this.sortKey.split("_");
      const q = new URLSearchParams({ type: this.type, sort, order });
      if (this.currentDir) q.set("dir", this.currentDir);
      if (this.search) q.set("search", this.search);
      if (refresh) q.set("refresh", "true");
      try {
        const data = await this.request("/api/files?" + q, {}, source);
        if (seq !== this.loadSeq) return;
        this.files = data;
        this.libraryRevision = data.libraryRevision || "";
        this.lastRefresh = Date.now();
        const p = await this.request("/api/progress", {}, source).catch(
          () => ({}),
        );
        if (seq === this.loadSeq) this.progressMap = p;
      } catch (e) {
        if (seq === this.loadSeq) {
          this.files = [];
          this.error = e.message + "。请保持共享设备在线。";
        }
      } finally {
        if (seq === this.loadSeq) this.loading = false;
      }
    },
    saveRefreshInterval() {
      localStorage.setItem("lan-refresh-seconds", String(this.refreshInterval));
      this.lastRefresh = Date.now();
      this.notify(
        this.refreshInterval
          ? `已设置每 ${this.refreshInterval} 秒刷新文件列表`
          : "已设为手动刷新文件列表",
      );
    },
    async checkLibrary() {
      if (this.checkingChanges || this.loading || document.hidden) return;
      this.checkingChanges = true;
      const source = this.source;
      try {
        const change = await this.request("/api/changes", {}, source);
        if (source !== this.source) return;
        if (this.libraryRevision && change.revision !== this.libraryRevision)
          await this.load();
        else this.libraryRevision = change.revision;
      } catch {
      } finally {
        this.checkingChanges = false;
      }
    },
    askDelete(f) {
      this.deleteTarget = {
        file: f,
        source: this.source,
        device: this.source?.name || this.self.name || "本机",
      };
    },
    async confirmDelete() {
      const target = this.deleteTarget;
      if (!target || this.deleting) return;
      this.deleting = true;
      try {
        const info = await this.request("/api/device", {}, target.source);
        if (!info.features?.includes("delete"))
          throw Error(this.upgradeHint(info));
        if (
          this.player.file?.id === target.file.id &&
          (this.player.source?.id || null) === (target.source?.id || null)
        )
          this.closePlayer();
        await this.request(
          "/api/file/" + target.file.id,
          {
            method: "DELETE",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              size: target.file.size,
              mtime: target.file.mtime,
            }),
          },
          target.source,
        );
        this.playQueue = this.playQueue.filter((f) => f.id !== target.file.id);
        this.deleteTarget = null;
        if (this.source === target.source) await this.load();
        this.notify(
          "已从「" +
            target.device +
            "」删除原文件，其他设备的文件列表会同步更新",
        );
      } catch (e) {
        this.notify(e.message);
      } finally {
        this.deleting = false;
      }
    },
    debouncedLoad() {
      clearTimeout(this.debounceTimer);
      this.debounceTimer = setTimeout(() => this.load(), 250);
    },
    async connectManual() {
      this.connecting = true;
      try {
        let raw = this.manualURL.trim();
        if (!/^https?:\/\//i.test(raw)) raw = "http://" + raw;
        const url = new URL(raw);
        if (
          !["http:", "https:"].includes(url.protocol) ||
          url.username ||
          url.password
        )
          throw Error("请输入有效的 HTTP 设备地址");
        const d = {
          url:
            url.origin +
            (url.searchParams.has("t")
              ? "/?t=" + encodeURIComponent(url.searchParams.get("t"))
              : ""),
        };
        const info = await this.request("/api/device", {}, d);
        if (info.version !== 1 || !info.id)
          throw Error("该地址不是兼容的局域影库设备");
        Object.assign(d, info);
        this.manuals = this.manuals.filter((x) => x.id !== d.id).concat(d);
        localStorage.setItem("lan-peers", JSON.stringify(this.manuals));
        await this.discover();
        this.settings = false;
        await this.selectDevice(d);
        this.manualURL = "";
      } catch (e) {
        this.notify(e.message);
      } finally {
        this.connecting = false;
      }
    },
    async openSettings() {
      this.settings = true;
      this.refreshNative();
      this.cacheLimit = this.cache.limitGb || 20;
      this.localDirs = (
        await this.request("/api/dirs", {}, null).catch(() => ({ dirs: [] }))
      ).dirs;
    },
    async addDir() {
      if (!this.newDir.trim()) return;
      this.dirBusy = true;
      this.dirError = "";
      try {
        const d = await this.request(
          "/api/dirs",
          {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ path: this.newDir.trim() }),
          },
          null,
        );
        this.localDirs = d.dirs;
        if (!this.source) this.dirs = d.dirs;
        this.newDir = "";
        await this.load(true);
      } catch (e) {
        this.dirError = e.message;
      } finally {
        this.dirBusy = false;
      }
    },
    async removeDir(path) {
      this.dirBusy = true;
      try {
        const d = await this.request(
          "/api/dirs",
          {
            method: "DELETE",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ path }),
          },
          null,
        );
        this.localDirs = d.dirs;
        if (!this.source) {
          this.dirs = d.dirs;
          this.currentDir = "";
          await this.load(true);
        }
      } catch (e) {
        this.dirError = e.message;
      } finally {
        this.dirBusy = false;
      }
    },
    pickFiles() {
      window.LanCinema.pickFiles();
    },
    unshare(f) {
      window.LanCinema.unshare(f.id);
      this.load(true);
    },
    mediaURL(f, source = this.source) {
      return this.apiURL(
        "/api/file/" +
          f.id +
          "/stream?rev=" +
          encodeURIComponent(f.size + "-" + f.mtime),
        source,
      );
    },
    favoriteKey(f, source = this.source) {
      return (source?.id || this.self.id || "local") + ":" + f.id;
    },
    bookmark(f, source = this.source) {
      return {
        key: this.favoriteKey(f, source),
        file: { ...f },
        source: source
          ? {
              id: source.id,
              name: source.name,
              url: source.url,
              platform: source.platform,
            }
          : null,
        deviceId: source?.id || this.self.id,
        deviceName: source?.name || this.self.name || "本机",
        url: this.mediaURL(f, source),
        added: Date.now(),
      };
    },
    isFavorite(f, source = this.source) {
      return this.favorites.some((b) => b.key === this.favoriteKey(f, source));
    },
    toggleBookmark(bookmark) {
      try {
        const exists = this.favorites.some((b) => b.key === bookmark.key);
        if (
          this.native &&
          typeof window.LanCinema.toggleFavorite === "function"
        ) {
          this.favorites = JSON.parse(
            window.LanCinema.toggleFavorite(JSON.stringify(bookmark)),
          );
        } else {
          const next = this.favorites.filter((b) => b.key !== bookmark.key);
          if (!exists) next.unshift(bookmark);
          localStorage.setItem("lan-favorites", JSON.stringify(next));
          this.favorites = next;
        }
        this.notify(exists ? "已取消收藏" : "已收藏，可在“收藏视频”中查看");
      } catch (e) {
        this.notify("收藏保存失败：" + e.message);
      }
    },
    toggleFavorite(f, source = this.source) {
      this.toggleBookmark(this.bookmark(f, source));
    },
    showFavorites() {
      this.refreshNative();
      this.view = "favorites";
    },
    favoriteSource(b) {
      if (b.deviceId === this.self.id || !b.source) return null;
      const latest = this.devices.find((d) => d.id === b.deviceId);
      if (!latest) return b.source;
      const url = new URL(latest.url);
      const token = new URL(b.source.url).searchParams.get("t");
      if (token && !url.searchParams.has("t")) url.searchParams.set("t", token);
      return { ...b.source, ...latest, url: url.href };
    },
    async playFavorite(b) {
      if (this.openingFavorite) return;
      this.openingFavorite = b.key;
      try {
        this.refreshNative();
        const offline = this.downloads.find(
          (d) => d.complete && (d.favorite?.key === b.key || d.url === b.url),
        );
        if (this.native && offline) {
          this.playDownload(offline);
          return;
        }
        const source = this.favoriteSource(b);
        const device = await this.request("/api/device", {}, source);
        if (b.deviceId && device.id !== b.deviceId)
          throw Error(
            "此地址已属于其他设备，请连接收藏的来源设备「" +
              b.deviceName +
              "」后重试",
          );
        const file = device.features?.includes("file-info")
          ? await this.request("/api/file/" + b.file.id, {}, source)
          : b.file;
        this.queueSource = source;
        this.queueProgress = await this.request(
          "/api/progress",
          {},
          source,
        ).catch(() => ({}));
        this.playQueue = this.favorites
          .filter((x) => x.deviceId === b.deviceId)
          .map((x) => (x.key === b.key ? file : x.file));
        this.openItem(file, true);
      } catch (e) {
        this.notify(
          e.status === 404
            ? "收藏的源视频已删除或停止共享，可取消收藏；已下载的视频仍可离线播放"
            : e.message,
        );
      } finally {
        this.openingFavorite = "";
      }
    },
    download(f, source = this.source) {
      const url = this.mediaURL(f, source);
      if (this.native) {
        window.LanCinema.download(
          JSON.stringify({
            url,
            name: f.name,
            id: f.id,
            size: f.size,
            type: f.type,
            favorite: f.type === "video" ? this.bookmark(f, source) : undefined,
            progressUrl: this.apiURL("/api/progress/" + f.id, source),
          }),
        );
        this.notify("已加入下载，可在“下载与缓存”中查看");
      } else {
        const a = document.createElement("a");
        const u = new URL(url);
        u.searchParams.set("download", "1");
        a.href = u.href;
        a.download = f.name;
        document.body.append(a);
        a.click();
        a.remove();
      }
    },
    videoInfo(f, source = this.source) {
      return {
        url: this.mediaURL(f, source),
        name: f.name,
        size: f.size,
        type: f.type,
        favorite: this.bookmark(f, source),
        position: this.queueProgress[f.id] || 0,
        progressUrl: this.apiURL("/api/progress/" + f.id, source),
      };
    },
    openItem(f, keepQueue = false) {
      if (f.type === "video") {
        if (!keepQueue) {
          this.queueSource = this.source;
          this.queueProgress = { ...this.progressMap };
          this.playQueue = this.files
            .filter((x) => x.type === "video")
            .sort((a, b) =>
              a.name.localeCompare(b.name, "zh-CN", { numeric: true }),
            );
        }
        if (this.native) {
          window.LanCinema.play(
            JSON.stringify({
              ...this.videoInfo(f, this.queueSource),
              playlist: this.playQueue.map((x) =>
                this.videoInfo(x, this.queueSource),
              ),
            }),
          );
          return;
        }
        const rate = keepQueue ? this.player.rate : 1;
        this.closePlayer();
        this.player = {
          file: f,
          source: this.queueSource,
          rate,
          zoom: 1,
          seekPreview: null,
          x: 0,
          y: 0,
          controls: true,
          playing: false,
          current: 0,
          duration: 0,
          buffered: 0,
          waiting: false,
          fit: "contain",
          expanded: false,
          boost: false,
          error: "",
        };
        this.$nextTick(() => this.initVideo(f));
      } else if (f.type === "image") this.lightbox = this.mediaURL(f);
      else this.download(f);
    },
    playEpisode(index) {
      const f = this.playQueue[index];
      if (f) {
        this.playlistOpen = false;
        this.openItem(f, true);
      }
    },
    initVideo(f) {
      const v = this.$refs.video;
      v.src = this.mediaURL(f, this.player.source);
      this.resizeObserver = new ResizeObserver(() => this.resetZoom());
      this.resizeObserver.observe(this.$refs.stage);
      v.onerror = () =>
        (this.player.error =
          "这个视频的编码可能不被浏览器支持，可使用安卓 App 或下载后播放。");
      v.onloadedmetadata = () => {
        const pos = this.queueProgress[f.id] || 0;
        if (pos > 0 && pos < v.duration) v.currentTime = pos;
      };
      v.playbackRate = this.player.rate;
      this.saveTimer = setInterval(() => this.saveProgress(), 5000);
      v.play().catch(() => {});
    },
    saveProgress(clear = false) {
      const v = this.$refs.video,
        f = this.player.file;
      if (!f || !v || !Number.isFinite(v.duration)) return;
      const position =
        clear || v.currentTime >= v.duration * 0.98 ? 0 : v.currentTime;
      this.queueProgress[f.id] = position;
      if ((this.player.source?.id || null) === (this.source?.id || null))
        this.progressMap[f.id] = position;
      this.request(
        "/api/progress/" + f.id,
        {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ position }),
          keepalive: true,
        },
        this.player.source,
      ).catch(() => {});
    },
    closePlayer() {
      if (!this.player.file) return;
      this.saveProgress();
      clearInterval(this.saveTimer);
      this.pointerUpBoost();
      clearTimeout(this.controlsTimer);
      this.resizeObserver?.disconnect();
      const v = this.$refs.video;
      if (v) {
        v.pause();
        v.onloadedmetadata = null;
        v.onerror = null;
        v.removeAttribute("src");
        v.load();
      }
      this.pointerPoints = {};
      this.player = {
        file: null,
        rate: 1,
        zoom: 1,
        seekPreview: null,
        x: 0,
        y: 0,
        controls: true,
        playing: false,
        current: 0,
        duration: 0,
        buffered: 0,
        waiting: false,
        fit: "contain",
        boost: false,
      };
    },
    onEnded() {
      this.saveProgress(true);
      this.playEpisode(this.episodeIndex + 1);
    },
    seek(n) {
      const v = this.$refs.video;
      if (v && Number.isFinite(v.duration))
        v.currentTime = Math.max(0, Math.min(v.duration, v.currentTime + n));
    },
    setRate() {
      if (this.$refs.video) this.$refs.video.playbackRate = this.player.rate;
    },
    zoomAt(next, from, to = from) {
      const stage = this.$refs.stage;
      if (!stage) return;
      next = Math.max(1, Math.min(4, next));
      const ratio = next / this.player.zoom;
      this.player.x = Math.max(
        stage.clientWidth * (1 - next),
        Math.min(0, to.x - (from.x - this.player.x) * ratio),
      );
      this.player.y = Math.max(
        stage.clientHeight * (1 - next),
        Math.min(0, to.y - (from.y - this.player.y) * ratio),
      );
      this.player.zoom = next;
    },
    zoomBy(n, event) {
      const stage = this.$refs.stage;
      if (!stage) return;
      const rect = stage.getBoundingClientRect();
      const center = event
        ? { x: event.clientX - rect.left, y: event.clientY - rect.top }
        : { x: rect.width / 2, y: rect.height / 2 };
      this.zoomAt(this.player.zoom + n, center);
    },
    resetZoom() {
      this.player.zoom = 1;
      this.player.x = this.player.y = 0;
      this.cancelVideoGesture();
    },
    pointerDown(e) {
      if (e.target.closest("button,input,select")) return;
      if (e.pointerType === "mouse" && e.button !== 0) return;
      const rect = this.$refs.stage.getBoundingClientRect();
      const point = { x: e.clientX - rect.left, y: e.clientY - rect.top };
      this.pointerPoints[e.pointerId] = {
        ...point,
        startX: point.x,
        startY: point.y,
      };
      this.$refs.stage.setPointerCapture(e.pointerId);
      this.pointerUpBoost();
      if (Object.keys(this.pointerPoints).length > 1) {
        this.gestureMulti = true;
        this.player.seekPreview = null;
        this.gestureAxis = "";
        this.gestureMoved = true;
        this.player.controls = false;
        return;
      }
      this.gestureMulti = this.gestureMoved = false;
      this.gestureAxis = "";
      this.gestureStart = this.$refs.video?.currentTime || 0;
      this.holdTimer = setTimeout(() => {
        const v = this.$refs.video;
        if (!v || v.paused) return;
        this.player.boost = true;
        this.player.controls = false;
        v.playbackRate = Math.max(this.boostRate, this.player.rate);
      }, 400);
    },
    pointerMove(e) {
      const prev = this.pointerPoints[e.pointerId];
      if (!prev || !this.$refs.stage) return;
      const old = Object.values(this.pointerPoints);
      const rect = this.$refs.stage.getBoundingClientRect();
      const point = {
        ...prev,
        x: e.clientX - rect.left,
        y: e.clientY - rect.top,
      };
      this.pointerPoints[e.pointerId] = point;
      const points = Object.values(this.pointerPoints);
      if (points.length === 2) {
        const distance = (pts) =>
          Math.hypot(pts[0].x - pts[1].x, pts[0].y - pts[1].y);
        const center = (pts) => ({
          x: (pts[0].x + pts[1].x) / 2,
          y: (pts[0].y + pts[1].y) / 2,
        });
        if (distance(old) > 0)
          this.zoomAt(
            (this.player.zoom * distance(points)) / distance(old),
            center(old),
            center(points),
          );
      } else if (points.length === 1) {
        if (Math.hypot(point.x - prev.startX, point.y - prev.startY) > 8) {
          this.gestureMoved = true;
          this.pointerUpBoost();
        }
        if (this.gestureMoved && !this.gestureMulti && this.player.zoom > 1) {
          this.zoomAt(this.player.zoom, prev, point);
        } else if (
          this.gestureMoved &&
          !this.gestureMulti &&
          this.player.zoom === 1
        ) {
          const dx = point.x - prev.startX,
            dy = point.y - prev.startY;
          if (!this.gestureAxis && Math.max(Math.abs(dx), Math.abs(dy)) > 12)
            this.gestureAxis = Math.abs(dx) > Math.abs(dy) ? "seek" : "ignore";
          if (this.gestureAxis === "seek" && this.player.duration > 0) {
            const span = Math.min(120, Math.max(10, this.player.duration / 3));
            this.player.seekPreview = Math.max(
              0,
              Math.min(
                this.player.duration,
                this.gestureStart + (dx / rect.width) * span,
              ),
            );
            this.player.controls = true;
          }
        }
      }
    },
    pointerUpBoost() {
      clearTimeout(this.holdTimer);
      if (this.player.boost) {
        this.player.boost = false;
        this.setRate();
      }
    },
    pointerUp(e) {
      if (!this.pointerPoints[e.pointerId]) return;
      const held = this.player.boost;
      if (
        e.type === "pointerup" &&
        this.gestureAxis === "seek" &&
        this.player.seekPreview !== null &&
        this.$refs.video
      )
        this.$refs.video.currentTime = this.player.seekPreview;
      this.player.seekPreview = null;
      delete this.pointerPoints[e.pointerId];
      this.pointerUpBoost();
      if (
        !this.gestureMoved &&
        !this.gestureMulti &&
        !held &&
        e.type === "pointerup"
      ) {
        this.player.controls = !this.player.controls;
      }
      if (!Object.keys(this.pointerPoints).length)
        this.schedulePlayerControls();
    },
    cancelVideoGesture() {
      this.pointerUpBoost();
      this.pointerPoints = {};
      this.player.seekPreview = null;
    },
    schedulePlayerControls() {
      clearTimeout(this.controlsTimer);
      if (this.player.playing)
        this.controlsTimer = setTimeout(() => {
          this.player.controls = false;
        }, 4000);
    },
    updateVideo() {
      const v = this.$refs.video;
      if (!v) return;
      this.player.current = v.currentTime;
      this.player.duration = Number.isFinite(v.duration) ? v.duration : 0;
      this.player.playing = !v.paused && !v.ended;
      this.player.buffered = v.buffered.length
        ? v.buffered.end(v.buffered.length - 1)
        : 0;
      if (!this.player.playing) {
        this.player.controls = true;
        this.pointerUpBoost();
      }
    },
    toggleVideo() {
      const v = this.$refs.video;
      if (!v) return;
      this.pointerUpBoost();
      if (v.paused) v.play().catch(() => {});
      else v.pause();
      this.schedulePlayerControls();
    },
    seekVideo(e) {
      if (this.$refs.video)
        this.$refs.video.currentTime = Number(e.target.value);
      this.player.controls = true;
      this.schedulePlayerControls();
    },
    videoTime(n) {
      const s = Math.max(0, Math.floor(n || 0));
      return (
        (s >= 3600 ? Math.floor(s / 3600) + ":" : "") +
        String(Math.floor(s / 60) % 60).padStart(2, "0") +
        ":" +
        String(s % 60).padStart(2, "0")
      );
    },
    async fullscreen() {
      try {
        if (document.fullscreenElement) await document.exitFullscreen();
        else if (this.$refs.stage.requestFullscreen)
          await this.$refs.stage.requestFullscreen();
        else this.$refs.video.webkitEnterFullscreen();
      } catch {
        this.notify("请使用视频自带的全屏按钮");
      }
    },
    key(e) {
      if (e.target.matches("input,select,textarea")) return;
      if (e.key === "Escape") {
        this.closePlayer();
        this.lightbox = "";
        this.settings = false;
        this.showDirManager = false;
      }
      if (!this.player.file) return;
      if (e.key === "ArrowRight") {
        e.preventDefault();
        this.seek(10);
      }
      if (e.key === "ArrowLeft") {
        e.preventDefault();
        this.seek(-10);
      }
      if (e.code === "Space") {
        e.preventDefault();
        const v = this.$refs.video;
        v.paused ? v.play().catch(() => {}) : v.pause();
      }
    },
    downloadSpeedLabel(d) {
      return d.speed > 0 ? this.fmtSize(d.speed) + "/s" : "等待数据…";
    },
    saveDownloadSort() {
      localStorage.setItem("lan-download-sort", this.downloadSort);
    },
    refreshNative() {
      if (!this.native) return;
      try {
        this.cache = JSON.parse(window.LanCinema.cacheInfo());
        this.downloads = JSON.parse(window.LanCinema.downloads());
        if (typeof window.LanCinema.favorites === "function")
          this.favorites = JSON.parse(window.LanCinema.favorites());
      } catch (e) {
        this.notify(e.message);
      }
    },
    showDownloads() {
      this.view = "downloads";
      this.refreshNative();
    },
    clearCache() {
      if (!this.native) return;
      window.LanCinema.clearCache();
      this.notify("正在清理临时缓存，完整下载会保留");
      setTimeout(() => this.refreshNative(), 1000);
    },
    saveCacheLimit() {
      if (
        !Number.isInteger(this.cacheLimit) ||
        this.cacheLimit < 1 ||
        this.cacheLimit > 1024
      ) {
        this.notify("请输入 1–1024 之间的整数 GB");
        return;
      }
      window.LanCinema.setCacheLimit(this.cacheLimit);
      this.refreshNative();
      this.notify("临时缓存上限已保存");
    },
    manageLock() {
      window.LanCinema.manageLock();
    },
    playDownload(d) {
      const queue = this.sortedDownloads.filter(
        (x) => !x.type || x.type === "video",
      );
      const info = (x) => ({
        url: x.url,
        name: x.name,
        size: x.total,
        type: x.type,
        position: 0,
        offline: x.complete,
        progressUrl: x.progressUrl,
        favorite: x.favorite,
      });
      window.LanCinema.play(
        JSON.stringify({
          ...info(d),
          playlist: queue.map(info),
        }),
      );
    },
    exportDownload(d) {
      window.LanCinema.exportDownload(d.id);
    },
    downloadAction(d, action) {
      window.LanCinema.downloadAction(d.id, action);
      setTimeout(() => this.refreshNative(), 500);
    },
  },
  async mounted() {
    window.lanBack = () => {
      if (
        this.deleteTarget ||
        this.player.file ||
        this.lightbox ||
        this.settings ||
        this.showDirManager
      ) {
        this.closePlayer();
        this.lightbox = "";
        this.settings = false;
        this.showDirManager = false;
        if (!this.deleting) this.deleteTarget = null;
        return true;
      }
      if (this.view !== "library") {
        this.view = "library";
        return true;
      }
      return false;
    };
    window.addEventListener("keydown", this.key);
    window.addEventListener("pointermove", this.pointerMove, { passive: true });
    window.addEventListener("pointerup", this.pointerUp);
    window.addEventListener("pointercancel", this.pointerUp);
    window.addEventListener("blur", this.cancelVideoGesture);
    document.addEventListener("visibilitychange", this.cancelVideoGesture);
    window.addEventListener("lanfileschanged", () => {
      this.load(true);
      this.refreshNative();
    });
    window.addEventListener("lanresume", () => {
      this.refreshNative();
      this.checkLibrary();
      this.request("/api/progress")
        .then((p) => (this.progressMap = p))
        .catch(() => {});
    });
    window.addEventListener("pagehide", () => this.saveProgress());
    this.request("/api/device", {}, null)
      .then((d) => {
        this.self = d;
        if (!this.source) this.sourceInfo = d;
      })
      .catch(() => {});
    this.request("/api/access", {}, null)
      .then((d) => (this.accessUrls = d.lan || []))
      .catch(() => {});
    this.request("/api/dirs", {}, null)
      .then((d) => {
        this.dirs = d.dirs || [];
        this.localDirs = this.dirs;
      })
      .catch(() => {});
    this.discover();
    this.refreshNative();
    this.pollTimer = setInterval(() => {
      if (document.hidden) return;
      if (
        Date.now() - this.lastRefresh >= this.refreshInterval * 1000 &&
        this.refreshInterval > 0 &&
        this.view === "library" &&
        !this.player.file &&
        !this.loading
      )
        this.load(true);
      else this.checkLibrary();
    }, 15000);
    this.discoveryTimer = setInterval(() => {
      if (!document.hidden) this.discover();
    }, 30000);
    this.downloadsTimer = setInterval(() => {
      if (this.view === "downloads") this.refreshNative();
    }, 1000);
    await this.load();
  },
  beforeUnmount() {
    clearInterval(this.pollTimer);
    clearInterval(this.discoveryTimer);
    clearInterval(this.downloadsTimer);
    this.closePlayer();
    window.removeEventListener("keydown", this.key);
    window.removeEventListener("pointermove", this.pointerMove);
    window.removeEventListener("pointerup", this.pointerUp);
    window.removeEventListener("pointercancel", this.pointerUp);
    window.removeEventListener("blur", this.cancelVideoGesture);
    document.removeEventListener("visibilitychange", this.cancelVideoGesture);
  },
}).mount("#app");
