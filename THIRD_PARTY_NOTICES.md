# Third-party software

LAN Cinema source is distributed under the MIT license. Its dependencies retain
their own licenses and notices:

| Software | License | Source |
| --- | --- | --- |
| Vue 3 (vendored browser build) | MIT, copyright Evan You and contributors | https://github.com/vuejs/core |
| FastAPI | MIT | https://github.com/fastapi/fastapi |
| Starlette | BSD-3-Clause | https://github.com/Kludex/starlette |
| Uvicorn | BSD-3-Clause | https://github.com/encode/uvicorn |
| python-zeroconf | LGPL-2.1-or-later | https://github.com/python-zeroconf/python-zeroconf |
| NanoHTTPD | BSD-3-Clause | https://github.com/NanoHttpd/nanohttpd |
| AndroidX Media3 | Apache-2.0 | https://github.com/androidx/media |
| Robolectric (tests only) | MIT | https://github.com/robolectric/robolectric |

Vue's copyright and license header are preserved in `static/vue.global.prod.js`.
Python dependencies are installed separately. FFmpeg is optional and is not
bundled. Android dependencies and their notices are packaged by Gradle; see
the linked projects for complete license texts and transitive dependencies.
