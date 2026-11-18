#!/usr/bin/env python3
"""Package the NERT Go server into per-platform zips under dist/."""
import os
import shutil
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST = os.path.join(ROOT, "dist")

TARGETS = {
    "nert-server-linux-amd64.zip": ("nert-server-linux-amd64", "nert-server"),
    "nert-server-linux-386.zip": ("nert-server-linux-386", "nert-server"),
    "nert-server-windows-amd64.zip": ("nert-server-windows-amd64.exe", "nert-server.exe"),
}

README = """NERT 服务端（可选组件）
================================

手机 App 不需要这个服务端也能修图。它只用于：在同一台电脑/局域网里，
用一个 HTTP 接口调用本机 Stable Diffusion 引擎出图。

目录
----
nert-server/
├── nert-server(.exe)   本 zip 里的可执行文件
├── bin/                放 stable-diffusion.cpp 的 sd-server（Windows 为 sd-server.exe）
└── models/             放 .safetensors / .gguf 权重，支持子目录

运行
----
1. 首次运行会自动生成 config/server-go.json，并打印实际使用的模型。
2. 浏览器不要用后台页面访问：/admin 出图控制台在本版已下线（不再注册路由）。
3. 接口：
   GET  /healthz                     健康检查
   POST /sdapi/v1/txt2img            文生图（A1111 兼容字段）
   POST /sdapi/v1/img2img            图生图 / 修图（init_images 为 base64）
   POST /v1/images/generations       OpenAI 兼容（Bearer Key）

注意
----
- 出图可能持续几分钟，反向代理必须放宽读超时（服务端本身写超时为 0，不超时）。
- 只建议在可信网络开放端口；需要远程访问请用 SSH 隧道：
  ssh -L 7860:127.0.0.1:7860 user@host
- 服务端不存放任何用户图片，请求体处理完即丢弃。
"""

CONFIG_SAMPLE = """{
  "port": 7860,
  "inner_port": 7861,
  "enable_api_key": true,
  "threads": 0,
  "steps": 40,
  "cfg_scale": 7.0,
  "width": 512,
  "height": 512,
  "autostart": true
}
"""


def main() -> None:
    for archive, (binary, exe_name) in TARGETS.items():
        src = os.path.join(DIST, binary)
        if not os.path.exists(src):
            print("skip missing", binary)
            continue
        mode = os.stat(src).st_mode
        out = os.path.join(DIST, archive)
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr(f"nert-server/{exe_name}", open(src, "rb").read())
            z.writestr("nert-server/README.txt", README)
            z.writestr("nert-server/config/server-go.sample.json", CONFIG_SAMPLE)
            z.writestr("nert-server/models/.gitkeep", "")
            z.writestr("nert-server/bin/.gitkeep", "")
        # keep the executable bit for the unpacked binary hint
        os.chmod(src, mode)
        print("wrote", os.path.relpath(out, ROOT), os.path.getsize(out), "bytes")
        os.remove(src)
        print("removed loose binary", binary)

    print("dist now:", sorted(os.listdir(DIST)))


if __name__ == "__main__":
    main()
