"""Настоящие JVM-тесты чистого Kotlin без Android SDK. Зависимости только из Maven Central."""
import os, pathlib, subprocess, urllib.request
ROOT=pathlib.Path(__file__).resolve().parents[1]
CACHE=ROOT/'.tools'/'jvm';CACHE.mkdir(parents=True,exist_ok=True)
ARTIFACTS=[('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.1.21'),('org.jetbrains.kotlin','kotlin-stdlib','2.1.21'),('org.jetbrains.kotlin','kotlin-script-runtime','2.1.21'),('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),('org.jetbrains','annotations','13.0'),('org.jetbrains.kotlin','kotlin-serialization-compiler-plugin-embeddable','2.1.21'),('org.jetbrains.kotlinx','kotlinx-serialization-core-jvm','1.8.1'),('org.jetbrains.kotlinx','kotlinx-serialization-json-jvm','1.8.1'),('junit','junit','4.13.2'),('org.hamcrest','hamcrest-core','1.3')]
def fetch(item):
    group,name,version=item;namefile=f'{name}-{version}.jar';target=CACHE/namefile
    if not target.exists():
        url='https://repo.maven.apache.org/maven2/'+group.replace('.','/')+'/'+name+'/'+version+'/'+namefile
        with urllib.request.urlopen(url,timeout=180) as src,open(target.with_suffix('.part'),'wb') as out:
            import shutil;shutil.copyfileobj(src,out)
        target.with_suffix('.part').replace(target)
    return target
if __name__=='__main__':
    import concurrent.futures
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool: jars=list(pool.map(fetch,ARTIFACTS))
    cp=os.pathsep.join(map(str,jars));out=CACHE/'classes';out.mkdir(exist_ok=True)
    source=ROOT/'app/src/main/java/com/savemy/singboxfork'
    files=[source/'data/Models.kt',source/'data/Importer.kt',source/'core/ConfigFactory.kt',source/'core/LocalApi.kt',ROOT/'app/src/test/java/com/savemy/singboxfork/data/CoreTest.kt',ROOT/'app/src/test/java/com/savemy/singboxfork/data/LocalApiTest.kt']
    command=['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-Xplugin='+str(jars[7]),'-jvm-target','1.8','-d',str(out)]+list(map(str,files))
    subprocess.run(command,cwd=ROOT,check=True)
    subprocess.run(['java','-cp',str(out)+os.pathsep+cp,'org.junit.runner.JUnitCore','com.savemy.singboxfork.data.CoreTest','com.savemy.singboxfork.data.LocalApiTest'],cwd=ROOT,check=True)
    subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-jvm-target','1.8','-d',str(out),str(ROOT/'tools/SyntaxCheck.kt')],cwd=ROOT,check=True)
    subprocess.run(['java','-cp',str(out)+os.pathsep+cp,'SyntaxCheckKt',str(ROOT/'app/src')],cwd=ROOT,check=True)
