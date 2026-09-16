#!/usr/bin/env python3
from pathlib import Path
import argparse, shutil, re, time

HOST="play.arl-samprpg.site"
PORT="7777"

def read(p): return p.read_text(encoding="utf-8-sig")
def write(p,s):
    p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(s,encoding="utf-8",newline="\n")
def die(s): raise SystemExit("[ARL PATCH] ERRO: "+s)

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

    b=r/".arl_patch_backup"/time.strftime("%Y%m%d_%H%M%S")
    backup_extra=[
        r/"app/src/main/java/com/samp/mobile/launcher/MainActivity.java",
        r/"app/src/main/java/com/samp/mobile/launcher/SplashActivity.java"
    ]
    for p in req+backup_extra:
        if p.exists():
            q=b/p.relative_to(r); q.parent.mkdir(parents=True,exist_ok=True); shutil.copy2(p,q)
    print("[ARL] backup:",b)

    # Native: settings.ini controla host/porta reais da sessão.
    m=read(maincpp).replace("\t\t//ReadSettingFile();","\t\tReadSettingFile();",1)
    m=m.replace(
      'pNetGame = new CNetGame("94.23.168.153", 2305, pSettings->Get().szNickName, pSettings->Get().szPassword);',
      'pNetGame = new CNetGame(pSettings->Get().szHost, pSettings->Get().iPort, pSettings->Get().szNickName, pSettings->Get().szPassword);',1)
    write(maincpp,m)

    # Remove repositório morto do upstream.
    rg=read(rootgradle)
    rg2=re.sub(
        r"(?m)^\s*maven\s*\{\s*url\s*['\"]https://mint\.splunk\.com/gradle/?['\"]\s*\}\s*$\n?",
        "", rg)
    if rg2 == rg and "mint.splunk.com" in rg:
        die("repositório Splunk encontrado em formato inesperado")
    write(rootgradle,rg2)

    # Build-safe: preserva package/JNI e remove keystore upstream.
    g=read(gradle)
    if 'applicationId "com.samp.mobile"' not in g:
        die("applicationId upstream inesperado")
    ss=g.find("    signingConfigs {"); ee=g.find("    compileSdk ",ss)
    if ss!=-1 and ee!=-1:
        g=g[:ss]+g[ee:]
    g=g.replace("            signingConfig signingConfigs.release\n","")
    if "com.github.amitshekhariitbhu:PRDownloader:1.0.1" in g:
        g=g.replace("com.github.amitshekhariitbhu:PRDownloader:1.0.1",
                    "com.github.amitshekhariitbhu:PRDownloader:1.0.2",1)
    write(gradle,g)

    # Phase 2: somente SplashActivity é MAIN/LAUNCHER.
    x=read(manifest)
    launcher_filter=re.compile(
        r"\s*<intent-filter>\s*"
        r"<action\s+android:name=\"android\.intent\.action\.MAIN\"\s*/>\s*"
        r"<category\s+android:name=\"android\.intent\.category\.LAUNCHER\"\s*/>\s*"
        r"</intent-filter>", re.S)
    x=launcher_filter.sub("",x)

    splash_re=re.compile(
        r'(<activity\s+[^>]*android:name="\.launcher\.SplashActivity"[^>]*>)(.*?)(</activity>)',
        re.S)
    sm=splash_re.search(x)
    if not sm: die("SplashActivity não encontrada no manifest")

    opening=sm.group(1)
    if 'android:exported=' in opening:
        opening=re.sub(r'android:exported="[^"]*"','android:exported="true"',opening)
    else:
        opening=opening[:-1]+'\n        android:exported="true">'
    launch='''\n        <intent-filter>\n            <action android:name="android.intent.action.MAIN" />\n            <category android:name="android.intent.category.LAUNCHER" />\n        </intent-filter>\n    '''
    replacement=opening+launch+sm.group(3)
    x=x[:sm.start()]+replacement+x[sm.end():]

    if 'android:name=".launcher.MainActivity"' not in x:
        die("MainActivity não encontrada")

    if 'firebase_analytics_collection_enabled' not in x:
        x=x.replace(
            '        android:windowSoftInputMode="adjustNothing">\n\n        <provider',
            '        android:windowSoftInputMode="adjustNothing">\n\n'
            '        <!-- ARL: não enviar telemetria/crashes ao projeto Firebase upstream. -->\n'
            '        <meta-data\n'
            '            android:name="firebase_analytics_collection_enabled"\n'
            '            android:value="false" />\n'
            '        <meta-data\n'
            '            android:name="firebase_crashlytics_collection_enabled"\n'
            '            android:value="false" />\n\n'
            '        <provider',1)
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

    # Overlay ARL: launcher, updater, splash, layouts e arte.
    for src in overlay.rglob("*"):
        if src.is_file():
            dst=r/src.relative_to(overlay)
            dst.parent.mkdir(parents=True,exist_ok=True)
            shutil.copy2(src,dst)

    mm=read(maincpp); gg=read(gradle); xx=read(manifest); rr=read(rootgradle)
    splash_block=re.search(
        r'<activity\s+[^>]*android:name="\.launcher\.SplashActivity"[^>]*>.*?</activity>',
        xx,re.S)
    checks=[
      "\t\tReadSettingFile();" in mm,
      "pSettings->Get().szHost" in mm,
      "pSettings->Get().iPort" in mm,
      'new CNetGame("94.23.168.153", 2305' not in mm,
      'applicationId "com.samp.mobile"' in gg,
      'android:name=".launcher.MainActivity"' in xx,
      splash_block is not None and "android.intent.category.LAUNCHER" in splash_block.group(0),
      xx.count("android.intent.category.LAUNCHER") == 1,
      "mint.splunk.com" not in rr,
      "com.github.amitshekhariitbhu:PRDownloader:1.0.1" not in gg,
      "com.github.amitshekhariitbhu:PRDownloader:1.0.2" in gg,
      (r/"app/src/main/res/drawable/arl_splash_banner.jpg").exists(),
      (r/"app/src/main/java/com/samp/mobile/launcher/ArlDataManager.java").exists()
    ]
    if not all(checks): die("auditoria pós-patch falhou")
    print("[ARL PATCH] OK -> "+HOST+":"+PORT)
    print("[ARL PATCH] Phase 2 DATA updater + splash ARL aplicados")
    print("[ARL PATCH] próximo: ./gradlew assembleDebug")

if __name__=="__main__": main()
