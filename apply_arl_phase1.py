#!/usr/bin/env python3
from pathlib import Path
import argparse, shutil, re, time

HOST="play.arl-samprpg.site"
PORT="7777"

def read(p): return p.read_text(encoding="utf-8-sig")
def write(p,s):
    p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(s,encoding="utf-8",newline="\n")
def die(s): raise SystemExit("[ARL PHASE1] ERRO: "+s)

def main():
    a=argparse.ArgumentParser()
    a.add_argument("repo")
    r=Path(a.parse_args().repo).resolve()

    maincpp=r/"app/src/main/cpp/samp/main.cpp"
    manifest=r/"app/src/main/AndroidManifest.xml"
    gradle=r/"app/build.gradle"
    rootgradle=r/"build.gradle"
    strings=r/"app/src/main/res/values/strings.xml"
    ini=r/"app/src/main/assets/settings.ini"
    settingscpp=r/"app/src/main/cpp/samp/settings.cpp"
    req=[maincpp,manifest,gradle,rootgradle,strings,ini,settingscpp]
    miss=[str(x.relative_to(r)) for x in req if not x.exists()]
    if miss: die("arquivos ausentes: "+", ".join(miss))

    m=read(maincpp)
    if 'new CNetGame("94.23.168.153", 2305' not in m and \
       "pSettings->Get().szHost" not in m:
        die("main.cpp divergente; não vou patchar no escuro")

    overlay=Path(__file__).resolve().parent/"arl_overlay"
    if not overlay.exists(): die("arl_overlay ausente")

    b=r/".arl_phase1_backup"/time.strftime("%Y%m%d_%H%M%S")
    for p in req+[r/"app/src/main/java/com/samp/mobile/launcher/MainActivity.java"]:
        if p.exists():
            q=b/p.relative_to(r); q.parent.mkdir(parents=True,exist_ok=True); shutil.copy2(p,q)
    print("[ARL] backup:",b)

    # Native: settings.ini passa a controlar o endpoint real da sessão.
    m=read(maincpp).replace("\t\t//ReadSettingFile();","\t\tReadSettingFile();",1)
    m=m.replace(
      'pNetGame = new CNetGame("94.23.168.153", 2305, pSettings->Get().szNickName, pSettings->Get().szPassword);',
      'pNetGame = new CNetGame(pSettings->Get().szHost, pSettings->Get().iPort, pSettings->Get().szNickName, pSettings->Get().szPassword);',1)
    write(maincpp,m)

    # Remove repositório morto do upstream. Ele quebra a resolução de dependências
    # antes mesmo do CMake por UnknownHost mint.splunk.com.
    rg=read(rootgradle)
    rg2=re.sub(
        r"(?m)^\s*maven\s*\{\s*url\s*['\"]https://mint\.splunk\.com/gradle/?['\"]\s*\}\s*$\n?",
        "",
        rg
    )
    if rg2 == rg and "mint.splunk.com" in rg:
        die("repositório Splunk encontrado em formato inesperado")
    rg=rg2
    write(rootgradle,rg)

    # App build: preserva package/JNI nesta fase e remove keystore upstream.
    g=read(gradle)
    if 'applicationId "com.samp.mobile"' not in g:
        die("applicationId upstream inesperado; Phase 1 BUILD-SAFE exige com.samp.mobile")
    ss=g.find("    signingConfigs {"); ee=g.find("    compileSdk ",ss)
    if ss!=-1 and ee!=-1:
        g=g[:ss]+g[ee:]
    g=g.replace("            signingConfig signingConfigs.release\n","")
    write(gradle,g)

    x=read(manifest)
    oldfilter='''        <intent-filter>\n            <action android:name="android.intent.action.MAIN" />\n            <category android:name="android.intent.category.LAUNCHER" />\n        </intent-filter>'''
    if oldfilter in x: x=x.replace(oldfilter,"",1)
    oldmain='''    <activity\n        android:name=".launcher.MainActivity"\n        android:screenOrientation="portrait"\n        android:theme="@style/Theme.AppCompat.NoActionBar"\n        android:windowSoftInputMode="adjustNothing">\n    </activity>'''
    newmain='''    <activity\n        android:name=".launcher.MainActivity"\n        android:screenOrientation="portrait"\n        android:theme="@style/Theme.AppCompat.NoActionBar"\n        android:windowSoftInputMode="adjustNothing"\n        android:exported="true">\n        <intent-filter>\n            <action android:name="android.intent.action.MAIN" />\n            <category android:name="android.intent.category.LAUNCHER" />\n        </intent-filter>\n    </activity>'''
    if oldmain in x: x=x.replace(oldmain,newmain,1)
    elif 'android:name=".launcher.MainActivity"' not in x: die("MainActivity não encontrada")
    if 'firebase_analytics_collection_enabled' not in x:
        x=x.replace('        android:windowSoftInputMode="adjustNothing">\n\n        <provider', '        android:windowSoftInputMode="adjustNothing">\n\n        <!-- Phase 1: não enviar telemetria/crashes para o projeto Firebase upstream. -->\n        <meta-data\n            android:name="firebase_analytics_collection_enabled"\n            android:value="false" />\n        <meta-data\n            android:name="firebase_crashlytics_collection_enabled"\n            android:value="false" />\n\n        <provider', 1)
    write(manifest,x)

    st=read(strings).replace('<string name="app_name">SA-MP Mobile</string>',
                             '<string name="app_name">Amazing Real Life</string>')
    write(strings,st)

    ii=read(ini)
    ii=re.sub(r'(?m)^host\s*=.*$','host = '+HOST,ii)
    ii=re.sub(r'(?m)^port\s*=.*$','port = '+PORT,ii)
    if not re.search(r'(?m)^version\s*=',ii):
        ii=ii.replace('name = Nick_Name','name = Nick_Name\nversion = 0.3.7-R3')
    write(ini,ii)

    for src in overlay.rglob("*"):
        if src.is_file():
            dst=r/src.relative_to(overlay); dst.parent.mkdir(parents=True,exist_ok=True)
            shutil.copy2(src,dst)

    mm=read(maincpp); gg=read(gradle); xx=read(manifest); rr=read(rootgradle)
    checks=[
      "\t\tReadSettingFile();" in mm,
      "pSettings->Get().szHost" in mm,
      "pSettings->Get().iPort" in mm,
      'new CNetGame("94.23.168.153", 2305' not in mm,
      'applicationId "com.samp.mobile"' in gg,
      'android:name=".launcher.MainActivity"' in xx,
      "android.intent.category.LAUNCHER" in xx,
      "mint.splunk.com" not in rr
    ]
    if not all(checks): die("auditoria pós-patch falhou")
    print("[ARL PHASE1] OK -> "+HOST+":"+PORT)
    print("[ARL PHASE1] repositório Maven morto mint.splunk.com removido")
    print("[ARL PHASE1] próximo: ./gradlew assembleDebug")

if __name__=="__main__": main()
