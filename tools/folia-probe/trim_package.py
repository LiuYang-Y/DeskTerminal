"""为"只跑 Web 版 dev server"裁剪 folia 的 package.json。

桌面专属依赖（Electron 全家桶、原生模块 onnxruntime-node/koffi、从 GitHub 拉 tarball 的
kugoumusicapi、测试用的 playwright）在 Web 可行性验证里用不到，且在本次网络环境下
根本装不上（github.com 走不通）。保留其余全部依赖，尽量贴近原始构建。
"""
import io
import json
import sys

path = sys.argv[1]
with io.open(path, "r", encoding="utf-8") as f:
    pkg = json.load(f)

DROP_DEPS = [
    "@xhayper/discord-rpc",   # Electron 侧 Discord RPC
    "electron-store",
    "electron-updater",
    "koffi",                  # 原生 FFI，桌面端音源
    "kugoumusicapi",          # 依赖 GitHub tarball，本机网络拉不到
    "onnxruntime-node",       # 原生 ONNX，桌面端人声分离
]
DROP_DEV = [
    "@playwright/test",       # 不跑 e2e
    "electron",
    "electron-builder",
]

removed = {"dependencies": [], "devDependencies": []}
for key, names in (("dependencies", DROP_DEPS), ("devDependencies", DROP_DEV)):
    for name in names:
        if name in pkg.get(key, {}):
            pkg[key].pop(name)
            removed[key].append(name)

with io.open(path, "w", encoding="utf-8") as f:
    json.dump(pkg, f, ensure_ascii=False, indent=2)

print("removed dependencies:", removed["dependencies"])
print("removed devDependencies:", removed["devDependencies"])
