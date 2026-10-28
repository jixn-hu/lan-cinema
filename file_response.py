"""Range responses that release disconnected readers and allow Windows deletion."""
from contextlib import asynccontextmanager
import os

import anyio
from starlette.datastructures import MutableHeaders
from starlette.responses import FileResponse


def open_shared(path):
    if os.name != "nt":
        return open(path, "rb")

    import ctypes
    from ctypes import wintypes
    import msvcrt

    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.CreateFileW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD,
                                  ctypes.c_void_p, wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE]
    kernel.CreateFileW.restype = wintypes.HANDLE
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    # Python's normal open() omits FILE_SHARE_DELETE. Our own video readers must
    # not prevent the user from deleting a watched video on Windows.
    handle = kernel.CreateFileW(os.fspath(path), 0x80000000, 0x1 | 0x2 | 0x4, None, 3, 0, None)
    if handle == ctypes.c_void_p(-1).value:
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        descriptor = msvcrt.open_osfhandle(handle, os.O_RDONLY | os.O_BINARY)
    except BaseException:
        kernel.CloseHandle(handle)
        raise
    try:
        return os.fdopen(descriptor, "rb")
    except BaseException:
        os.close(descriptor)
        raise


class SharedFileResponse(FileResponse):
    async def __call__(self, scope, receive, send):
        async with anyio.create_task_group() as tasks:
            async def stream():
                try:
                    await super(SharedFileResponse, self).__call__(scope, receive, send)
                finally:
                    tasks.cancel_scope.cancel()

            tasks.start_soon(stream)
            while True:
                if (await receive())["type"] == "http.disconnect":
                    tasks.cancel_scope.cancel()
                    break

    @asynccontextmanager
    async def _reader(self):
        file = await anyio.to_thread.run_sync(open_shared, self.path)
        try:
            yield anyio.wrap_file(file)
        finally:
            # A disconnected client cancels the response. Closing must survive
            # cancellation or Windows keeps the abandoned video handle locked.
            with anyio.CancelScope(shield=True):
                await anyio.to_thread.run_sync(file.close)

    async def _handle_simple(self, send, send_header_only, send_pathsend):
        await send({"type": "http.response.start", "status": self.status_code, "headers": self.raw_headers})
        if send_header_only:
            await send({"type": "http.response.body", "body": b"", "more_body": False})
            return
        async with self._reader() as file:
            while True:
                chunk = await file.read(self.chunk_size)
                more = len(chunk) == self.chunk_size
                await send({"type": "http.response.body", "body": chunk, "more_body": more})
                if not more:
                    break

    async def _handle_single_range(self, send, start, end, file_size, send_header_only):
        headers = MutableHeaders(raw=list(self.raw_headers))
        headers["content-range"] = f"bytes {start}-{end - 1}/{file_size}"
        headers["content-length"] = str(end - start)
        await send({"type": "http.response.start", "status": 206, "headers": headers.raw})
        if send_header_only:
            await send({"type": "http.response.body", "body": b"", "more_body": False})
            return
        async with self._reader() as file:
            await file.seek(start)
            while start < end:
                chunk = await file.read(min(self.chunk_size, end - start))
                start += len(chunk)
                more = bool(chunk) and start < end
                await send({"type": "http.response.body", "body": chunk, "more_body": more})
                if not more:
                    break

    async def _handle_multiple_ranges(self, send, ranges, file_size, send_header_only):
        from secrets import token_hex
        boundary = token_hex(13)
        length, header = self.generate_multipart(ranges, boundary, file_size, self.headers["content-type"])
        headers = MutableHeaders(raw=list(self.raw_headers))
        headers["content-type"] = f"multipart/byteranges; boundary={boundary}"
        headers["content-length"] = str(length)
        await send({"type": "http.response.start", "status": 206, "headers": headers.raw})
        if send_header_only:
            await send({"type": "http.response.body", "body": b"", "more_body": False})
            return
        async with self._reader() as file:
            for start, end in ranges:
                await send({"type": "http.response.body", "body": header(start, end), "more_body": True})
                await file.seek(start)
                while start < end:
                    chunk = await file.read(min(self.chunk_size, end - start))
                    if not chunk:
                        break
                    start += len(chunk)
                    await send({"type": "http.response.body", "body": chunk, "more_body": True})
                await send({"type": "http.response.body", "body": b"\r\n", "more_body": True})
            await send({"type": "http.response.body", "body": f"--{boundary}--".encode("latin-1"), "more_body": False})
