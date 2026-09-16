#!/usr/bin/env python3
from pathlib import Path
import argparse, os, shutil, re, time

HOST="play.arl-samprpg.site"
PORT="7777"
DEFAULT_VERSION_CODE="106"
DEFAULT_VERSION_NAME="1.0.0"

def read(p): return p.read_text(encoding="utf-8-sig")
def write(p,s):
    p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(s,encoding="utf-8",newline="\n")
def die(s): raise SystemExit("[ARL PATCH] ERRO: "+s)

def version_values():
    code=os.environ.get("ARL_VERSION_CODE",DEFAULT_VERSION_CODE).strip()
    name=os.environ.get("ARL_VERSION_NAME",DEFAULT_VERSION_NAME).strip()
    if not code.isdigit() or int(code) <= 0: die("ARL_VERSION_CODE inválido")
    if not name or any(c in name for c in '\r\n\t'): die("ARL_VERSION_NAME inválido")
    return code,name

def main():
    a=argparse.ArgumentParser()
    a.add_argument("repo")
    r=Path(a.parse_args().repo).resolve()
    version_code,version_name=version_values()

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

    # Build-safe: preserva namespace/JNI, substitui identidade de versão e remove a
    # chave upstream. Release pode usar uma chave ARL persistente via variáveis de ambiente.
    g=read(gradle)
    if 'applicationId "com.samp.mobile"' not in g:
        die("applicationId upstream inesperado")
    ss=g.find("    signingConfigs {"); ee=g.find("    compileSdk ",ss)
    if ss!=-1 and ee!=-1:
        g=g[:ss]+g[ee:]
    g=g.replace("            signingConfig signingConfigs.release\n","")
    g=re.sub(r'(?m)^\s*versionCode\s+\d+\s*$',f'        versionCode {version_code}',g,count=1)
    g=re.sub(r'(?m)^\s*versionName\s+"[^"]*"\s*$',f'        versionName "{version_name}"',g,count=1)

    signing_preamble='''def arlKeystorePath = System.getenv("ARL_KEYSTORE_PATH")\ndef arlKeystorePassword = System.getenv("ARL_KEYSTORE_PASSWORD")\ndef arlKeyAlias = System.getenv("ARL_KEY_ALIAS")\ndef arlKeyPassword = System.getenv("ARL_KEY_PASSWORD")\ndef arlSigningReady = arlKeystorePath && arlKeystorePassword && arlKeyAlias && arlKeyPassword\n\n'''
    # Gradle exige que plugins {} seja o primeiro bloco executável. Portanto as
    # variáveis de assinatura entram imediatamente antes de android {}, nunca antes de plugins {}.
    if "def arlKeystorePath" not in g:
        android_pos=g.find("android {")
        if android_pos < 0: die("bloco android ausente no Gradle")
        g=g[:android_pos]+signing_preamble+g[android_pos:]

    signing_block='''android {\n    if (arlSigningReady) {\n        signingConfigs {\n            arlRelease {\n                storeFile file(arlKeystorePath)\n                storePassword arlKeystorePassword\n                keyAlias arlKeyAlias\n                keyPassword arlKeyPassword\n            }\n        }\n    }'''
    g=g.replace("android {",signing_block,1)
    g=g.replace("        release {\n            firebaseCrashlytics {",
                "        release {\n            if (arlSigningReady) { signingConfig signingConfigs.arlRelease }\n            firebaseCrashlytics {",1)

    if "com.github.amitshekhariitbhu:PRDownloader:1.0.1" in g:
        g=g.replace("com.github.amitshekhariitbhu:PRDownloader:1.0.1",
                    "com.github.amitshekhariitbhu:PRDownloader:1.0.2",1)
    write(gradle,g)

    # Somente SplashActivity é MAIN/LAUNCHER.
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

    # Overlay ARL: launcher, updaters, splash, layouts e arte.
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
      f'versionCode {version_code}' in gg,
      f'versionName "{version_name}"' in gg,
      'def arlSigningReady' in gg,
      gg.find('plugins {') < gg.find('def arlKeystorePath') < gg.find('android {'),
      'android:name=".launcher.MainActivity"' in xx,
      splash_block is not None and "android.intent.category.LAUNCHER" in splash_block.group(0),
      xx.count("android.intent.category.LAUNCHER") == 1,
      "mint.splunk.com" not in rr,
      "com.github.amitshekhariitbhu:PRDownloader:1.0.1" not in gg,
      "com.github.amitshekhariitbhu:PRDownloader:1.0.2" in gg,
      (r/"app/src/main/res/drawable/arl_splash_banner.jpg").exists(),
      (r/"app/src/main/java/com/samp/mobile/launcher/ArlDataManager.java").exists(),
      (r/"app/src/main/java/com/samp/mobile/launcher/ArlAppUpdateManager.java").exists()
    ]
    if not all(checks): die("auditoria pós-patch falhou")
    print("[ARL PATCH] OK -> "+HOST+":"+PORT)
    print("[ARL PATCH] versionCode="+version_code+" versionName="+version_name)
    print("[ARL PATCH] DATA updater + APK updater + splash ARL aplicados")
    print("[ARL PATCH] próximo: ./gradlew assembleDebug")

if __name__=="__main__": main()
