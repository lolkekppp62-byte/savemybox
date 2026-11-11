"""Загрузка закреплённых публичных исходников для сверки API, без выполнения кода."""
import base64, concurrent.futures, json, os, pathlib, urllib.request
ROOT = pathlib.Path(__file__).resolve().parents[1]
FILES = {
 'config.go': ('sing-box','v1.14.4','experimental/libbox/config.go'),
 'quic.md': ('sing-box','v1.14.4','docs/configuration/shared/quic.md'),
 'iterator.go': ('sing-box','v1.14.4','experimental/libbox/iterator.go'),
 'clash_server.go': ('sing-box','v1.14.4','experimental/clashapi/server.go'),
 'clash_proxies.go': ('sing-box','v1.14.4','experimental/clashapi/proxies.go'),
 'LICENSE-sing-box': ('sing-box','v1.14.4','LICENSE'),
 'tuic.md': ('sing-box','v1.14.4','docs/configuration/outbound/tuic.md'),
 'hysteria2.md': ('sing-box','v1.14.4','docs/configuration/outbound/hysteria2.md'),
 'platform.go': ('sing-box','v1.14.4','experimental/libbox/platform.go'),
 'command_server.go': ('sing-box','v1.14.4','experimental/libbox/command_server.go'),
 'command_client.go': ('sing-box','v1.14.4','experimental/libbox/command_client.go'),
 'tun.go': ('sing-box','v1.14.4','experimental/libbox/tun.go'),
 'build_libbox.go': ('sing-box','v1.14.4','cmd/internal/build_libbox/main.go'),
 'go.mod': ('sing-box','v1.14.4','go.mod'),
 'BoxService.kt': ('sing-box-for-android','dev','app/src/main/java/io/nekohasekai/sfa/bg/BoxService.kt'),
 'VPNService.kt': ('sing-box-for-android','dev','app/src/main/java/io/nekohasekai/sfa/bg/VPNService.kt'),
}
def load(item):
 name,(repo,ref,path)=item
 req=urllib.request.Request(f'https://api.github.com/repos/SagerNet/{repo}/contents/{path}?ref={ref}',headers={'User-Agent':'SavemyFork-build-research'})
 try:
  data=json.load(urllib.request.urlopen(req,timeout=40))
  content=base64.b64decode(data['content'])
  target=ROOT/'reference'/name
  target.parent.mkdir(parents=True,exist_ok=True)
  if not target.exists(): target.write_bytes(content)
  return f'{name}: {len(content)} bytes'
 except Exception as e: return f'{name}: {e}'
if __name__=='__main__':
 with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
  print('\n'.join(pool.map(load,FILES.items())))
 for p in [pathlib.Path(os.environ.get('LOCALAPPDATA',''))/'Android'/'Sdk', pathlib.Path('C:/Program Files/Android/Android Studio/jbr')]:
  print(f'{p}: exists={p.exists()}')
