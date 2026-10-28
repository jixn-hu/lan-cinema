const { createApp } = Vue;
createApp({
  data() {
    return {
      native: !!window.LanCinema,
      view: "library",
      self: {},
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
    };
  },
  computed: {
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
    async request(path, options = {}, source = this.source) {
      const response = await fetch(this.apiURL(path, source), {
        ...options,
        signal: AbortSignal.timeout(12000),
      });
      if (!response.ok) {
        let info;
        try {
          info = await response.json();
        } catch {}
        throw Error(
          info?.detail ||
            `连接失败（${response.status}），请检查设备地址或访问令牌`,
        );
      }
      return response.json();
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
      const seq = ++this.loadSeq;
      this.loading = true;
      this.error = "";
      const [sort, order] = this.sortKey.split("_");
      const q = new URLSearchParams({ type: this.type, sort, order });
      if (this.currentDir) q.set("dir", this.currentDir);
      if (this.search) q.set("search", this.search);
      if (refresh) q.set("refresh", "true");
      try {
        const data = await this.request("/api/files?" + q);
        if (seq !== this.loadSeq) return;
        this.files = data;
        const p = await this.request("/api/progress").catch(() => ({}));
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
    openItem(f) {
      if (f.type === "video") {
        if (this.native) {
          window.LanCinema.play(
            JSON.stringify({
              url: this.mediaURL(f),
              name: f.name,
              position: this.progressMap[f.id] || 0,
              progressUrl: this.apiURL("/api/progress/" + f.id),
            }),
          );
          return;
        }
        this.closePlayer();
        this.player = {
          file: f,
          source: this.source,
          rate: 1,
          zoom: 1,
          fit: "contain",
          expanded: false,
          boost: false,
          error: "",
        };
        this.$nextTick(() => this.initVideo(f));
      } else if (f.type === "image") this.lightbox = this.mediaURL(f);
      else this.download(f);
    },
    initVideo(f) {
      const v = this.$refs.video;
      v.src = this.mediaURL(f, this.player.source);
      v.onerror = () =>
        (this.player.error =
          "这个视频的编码可能不被浏览器支持，可使用安卓 App 或下载后播放。");
      v.addEventListener(
        "loadedmetadata",
        () => {
          const pos = this.progressMap[f.id] || 0;
          if (pos > 0 && pos < v.duration) v.currentTime = pos;
        },
        { once: true },
      );
      this.saveTimer = setInterval(() => this.saveProgress(), 5000);
      v.play().catch(() => {});
    },
    saveProgress(clear = false) {
      const v = this.$refs.video,
        f = this.player.file;
      if (!f || !v || !Number.isFinite(v.duration)) return;
      const position =
        clear || v.currentTime >= v.duration * 0.98 ? 0 : v.currentTime;
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
      clearTimeout(this.holdTimer);
      const v = this.$refs.video;
      if (v) {
        v.pause();
        v.removeAttribute("src");
        v.load();
      }
      this.pointerPoints = {};
      this.player = {
        file: null,
        rate: 1,
        zoom: 1,
        fit: "contain",
        boost: false,
      };
    },
    onEnded() {
      this.saveProgress(true);
    },
    seek(n) {
      const v = this.$refs.video;
      if (v && Number.isFinite(v.duration))
        v.currentTime = Math.max(0, Math.min(v.duration, v.currentTime + n));
    },
    setRate() {
      if (this.$refs.video) this.$refs.video.playbackRate = this.player.rate;
    },
    zoomBy(n) {
      this.player.zoom = Math.max(1, Math.min(3, this.player.zoom + n));
    },
    pointerDown(e) {
      this.pointerPoints[e.pointerId] = { x: e.clientX, y: e.clientY };
      const pts = Object.values(this.pointerPoints);
      clearTimeout(this.holdTimer);
      if (pts.length === 2) {
        this.pinchDistance = Math.hypot(
          pts[0].x - pts[1].x,
          pts[0].y - pts[1].y,
        );
        this.pointerUpBoost();
        return;
      }
      if (
        e.target === this.$refs.video &&
        e.offsetY > this.$refs.video.clientHeight - 55
      )
        return;
      this.holdTimer = setTimeout(() => {
        if (!this.$refs.video) return;
        this.player.boost = true;
        this.$refs.video.playbackRate = Math.max(
          this.boostRate,
          this.player.rate,
        );
      }, 450);
    },
    pointerMove(e) {
      if (!this.pointerPoints[e.pointerId]) return;
      this.pointerPoints[e.pointerId] = { x: e.clientX, y: e.clientY };
      const pts = Object.values(this.pointerPoints);
      if (pts.length === 2) {
        const dist = Math.hypot(pts[0].x - pts[1].x, pts[0].y - pts[1].y);
        if (this.pinchDistance) this.zoomBy((dist - this.pinchDistance) / 200);
        this.pinchDistance = dist;
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
      delete this.pointerPoints[e.pointerId];
      this.pinchDistance = 0;
      this.pointerUpBoost();
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
    refreshNative() {
      if (!this.native) return;
      try {
        this.cache = JSON.parse(window.LanCinema.cacheInfo());
        this.downloads = JSON.parse(window.LanCinema.downloads());
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
      window.LanCinema.play(
        JSON.stringify({
          url: d.url,
          name: d.name,
          position: 0,
          offline: d.complete,
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
        this.player.file ||
        this.lightbox ||
        this.settings ||
        this.showDirManager
      ) {
        this.closePlayer();
        this.lightbox = "";
        this.settings = false;
        this.showDirManager = false;
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
    window.addEventListener("lanfileschanged", () => {
      this.load(true);
      this.refreshNative();
    });
    window.addEventListener("pagehide", () => this.saveProgress());
    this.request("/api/device", {}, null)
      .then((d) => (this.self = d))
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
      this.discover();
      if (this.view === "downloads") this.refreshNative();
    }, 4000);
    await this.load();
  },
  beforeUnmount() {
    clearInterval(this.pollTimer);
    this.closePlayer();
    window.removeEventListener("keydown", this.key);
    window.removeEventListener("pointermove", this.pointerMove);
  },
}).mount("#app");
