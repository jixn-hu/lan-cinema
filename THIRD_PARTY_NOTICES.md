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

`file_response.py` adapts Starlette's file response transmission. The following
BSD-3-Clause notice applies to those portions:

Copyright © 2018, Encode OSS Ltd. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.
* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.
* Neither the name of the copyright holder nor the names of its contributors
  may be used to endorse or promote products derived from this software without
  specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
