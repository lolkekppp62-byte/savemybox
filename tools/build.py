"""Сборка без глобальной установки инструментов. JDK 17, Go и Android SDK устанавливает пользователь."""
import argparse, hashlib, json, os, pathlib, re, shutil, subprocess, sys, tarfile, urllib.request, zipfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
CORE='v1.14.4'
COMMIT='c005f35300ce76427dc285481ec5695f5b179cd2'
GRADLE='8.11.1'
NDK='26.1.10909125'
def run(args,cwd=ROOT,env=None):
    print('+ '+' '.join(map(str,args)),flush=True)
    subprocess.run(list(map(str,args)),cwd=cwd,env=env,check=True)
def download(url,path):
    path.parent.mkdir(parents=True,exist_ok=True)
    if path.exists(): return
    tmp=path.with_suffix(path.suffix+'.part')
    req=urllib.request.Request(url,headers={'User-Agent':'SavemyBox-build'})
    with urllib.request.urlopen(req,timeout=180) as source,open(tmp,'wb') as target: shutil.copyfileobj(source,target)
    tmp.replace(path)
def check():
    java=pathlib.Path(os.environ['JAVA_HOME'])/'bin'/'java' if os.environ.get('JAVA_HOME') else 'java'
    text=subprocess.run([str(java),'-version'],capture_output=True,text=True).stderr
    if not re.search(r'version "17\.',text): raise RuntimeError('Нужен JDK 17. Укажите JAVA_HOME. Найдено: '+text.splitlines()[0])
    if os.environ.get('JAVA_HOME'): os.environ['PATH']=str(pathlib.Path(os.environ['JAVA_HOME'])/'bin')+os.pathsep+os.environ['PATH']
    if not shutil.which('go'): raise RuntimeError('Установите Go >= 1.23.0 (go.dev/dl), затем откройте новый терминал.')
    version=subprocess.check_output(['go','version'],text=True)
    parts=re.search(r'go(\d+)\.(\d+)(?:\.(\d+))?',version)
    if not parts or tuple(int(x or 0) for x in parts.groups())<(1,23,0): raise RuntimeError('Нужен Go >= 1.23.0')
    sdk=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk: raise RuntimeError('Укажите ANDROID_HOME: каталог Android SDK с platform 35 и NDK '+NDK)
    sdk=pathlib.Path(sdk)
    for item in ['platforms/android-35/android.jar','ndk/'+NDK]:
        if not (sdk/item).exists(): raise RuntimeError('Установите через SDK Manager: '+item)
    os.environ['ANDROID_HOME']=str(sdk);os.environ['ANDROID_NDK_HOME']=str(sdk/'ndk'/NDK)
    return sdk

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');parser.add_argument('--install',action='store_true');args=parser.parse_args()
    sdk=check()
    if args.check: print('Предпосылки сборки: OK');return
    tools=ROOT/'.tools';tools.mkdir(exist_ok=True)
    archive=tools/f'gradle-{GRADLE}-bin.zip'
    download(f'https://services.gradle.org/distributions/gradle-{GRADLE}-bin.zip',archive)
    checksum=tools/(archive.name+'.sha256');download(f'https://services.gradle.org/distributions/{archive.name}.sha256',checksum)
    if hashlib.sha256(archive.read_bytes()).hexdigest()!=checksum.read_text().strip(): raise RuntimeError('Неверная контрольная сумма Gradle')
    gradle=tools/f'gradle-{GRADLE}'
    if not gradle.exists():
        with zipfile.ZipFile(archive) as z: z.extractall(tools)
    upstream=ROOT/'.upstream';upstream.mkdir(exist_ok=True)
    core=upstream/f'sing-box-{COMMIT}'
    if not core.exists():
        source=tools/f'{COMMIT}.zip';download(f'https://codeload.github.com/SagerNet/sing-box/zip/{COMMIT}',source)
        with zipfile.ZipFile(source) as z: z.extractall(upstream)
    aar=ROOT/'app/libs/libbox.aar'
    stamp=aar.with_suffix('.json')
    tags='with_gvisor,with_quic,with_utls,with_clash_api'
    expected={'commit':COMMIT,'tags':tags,'api':26,'gomobile':'v0.1.12'}
    valid=False
    if aar.exists() and stamp.exists():
        metadata=json.loads(stamp.read_text())
        valid=all(metadata.get(k)==v for k,v in expected.items()) and metadata.get('sha256')==hashlib.sha256(aar.read_bytes()).hexdigest()
    if not valid:
        env=os.environ.copy();env['GOBIN']=str(tools/'gobin');env['PATH']=env['GOBIN']+os.pathsep+env['PATH']
        run(['go','install','github.com/sagernet/gomobile/cmd/gomobile@v0.1.12'],core,env)
        run(['go','install','github.com/sagernet/gomobile/cmd/gobind@v0.1.12'],core,env)
        mobile=tools/'gobin'/('gomobile.exe' if os.name=='nt' else 'gomobile')
        run([mobile,'init'],core,env)
        aar.parent.mkdir(parents=True,exist_ok=True)
        # Только требуемые транспорты; не включаем Naive/SSH/Tailscale с лишними нативными зависимостями.
        run([mobile,'bind','-v','-o',aar,'-target','android','-androidapi','26','-javapkg=io.nekohasekai','-libname=box','-tags',tags,'-trimpath','-buildvcs=false','-ldflags',f'-X github.com/sagernet/sing-box/constant.Version={CORE[1:]}','./experimental/libbox'],core,env)
        stamp.write_text(json.dumps(dict(expected,sha256=hashlib.sha256(aar.read_bytes()).hexdigest()),indent=2))
    launcher=gradle/'bin'/('gradle.bat' if os.name=='nt' else 'gradle')
    if os.name!='nt': launcher.chmod(0o755)
    run([launcher,'--no-daemon',':app:testDebugUnitTest',':app:lintDebug',':app:assembleDebug'])
    apk=ROOT/'app/build/outputs/apk/debug/app-debug.apk'
    print('APK: '+str(apk))
    if args.install: run([sdk/'platform-tools'/('adb.exe' if os.name=='nt' else 'adb'),'install','-r',apk])
if __name__=='__main__':
    try: main()
    except Exception as e: print('Сборка остановлена: '+str(e),file=sys.stderr);sys.exit(1)
