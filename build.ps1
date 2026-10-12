param([string]$SDKRoot=$env:ANDROID_HOME,[string]$JavaHome=$env:JAVA_HOME,[string]$Api3Jar,[string]$Version='0.4.0',[int]$VersionCode=4)
$ErrorActionPreference='Stop'
$project=$PSScriptRoot
if(-not $SDKRoot -or -not $JavaHome){throw 'Set ANDROID_HOME and JAVA_HOME, or pass SDKRoot and JavaHome'}
$jdk=$JavaHome
$bt=Join-Path $SDKRoot 'build-tools/35.0.0'
$modern=Join-Path $SDKRoot 'platforms/android-35/android.jar'
if(-not $Api3Jar){$Api3Jar=Join-Path $SDKRoot 'platforms/android-3/android.jar'}
$api=(Resolve-Path $Api3Jar).Path
$build=Join-Path $project '.build'
$env:JAVA_HOME=$jdk
$env:PATH="$jdk/bin;$env:PATH"
& python "$project/tools/dependencies.py"
if($LASTEXITCODE -ne 0){throw 'Dependency fetch failed'}
& python "$project/tools/shade-bc.py"
if($LASTEXITCODE -ne 0){throw 'Crypto relocation failed'}
New-Item -ItemType Directory -Force "$build/generated","$build/classes","$build/dex" | Out-Null
& "$bt/aapt2.exe" compile --dir "$project/res" -o "$build/resources.zip"
if($LASTEXITCODE -ne 0){throw 'Resource compilation failed'}
& "$bt/aapt2.exe" link -o "$build/base.apk" --manifest "$project/AndroidManifest.xml" -I $modern -A "$project/assets" --java "$build/generated" --min-sdk-version 3 --target-sdk-version 28 --version-code $VersionCode --version-name $Version --no-auto-version --no-version-vectors "$build/resources.zip"
if($LASTEXITCODE -ne 0){throw 'Resource link failed'}
$sources=@(Get-ChildItem "$project/src","$build/generated" -Recurse -Filter '*.java' | ForEach-Object FullName)

$libs="$project/libs/shaded"
& "$jdk/bin/javac.exe" -encoding UTF-8 -source 8 -target 8 -classpath "$api;$libs/bcprov.jar;$libs/bctls.jar;$libs/bcutil.jar" -d "$build/classes" $sources
if($LASTEXITCODE -ne 0){throw 'API3 compilation failed'}
& "$jdk/bin/jar.exe" cf "$build/app.jar" -C "$build/classes" .
@'
-dontobfuscate
-keep class org.jellylegacy.stoneage.** { *; }
-keep class org.jellylegacy.net.** { *; }
-keep class org.jellylegacy.stoneage.LegacyHttp** { *; }
-keep class org.jellylegacy.crypto.jce.provider.** { *; }
-keep class org.jellylegacy.crypto.jcajce.provider.** { *; }
-dontwarn javax.naming.**
-dontwarn javax.security.**
-dontwarn java.lang.management.**
-dontwarn java.security.cert.CertificateRevokedException
-dontwarn java.security.cert.CertPathChecker
-dontwarn java.security.cert.Extension
-dontwarn java.security.cert.PKIXRevocationChecker**
-dontwarn java.lang.invoke.**
-dontwarn sun.**
-dontwarn java.util.Base64**
-dontwarn java.lang.Module
-dontwarn java.lang.Record
'@ | Set-Content "$build/rules.pro"
& "$jdk/bin/java.exe" -Xmx1024m -cp "$bt/lib/d8.jar" com.android.tools.r8.R8 --release --min-api 3 --lib $api --pg-conf "$build/rules.pro" --output "$build/dex" "$build/app.jar" "$libs/bcprov.jar" "$libs/bctls.jar" "$libs/bcutil.jar"
if($LASTEXITCODE -ne 0){throw 'DEX failed'}
Copy-Item "$build/base.apk" "$build/unsigned.apk" -Force
& "$jdk/bin/jar.exe" uf "$build/unsigned.apk" -C "$build/dex" classes.dex
$apk=Join-Path $build "JellyStone-Video-$Version-debug.apk"
& "$bt/zipalign.exe" -f -p 4 "$build/unsigned.apk" $apk
if($LASTEXITCODE -ne 0){throw 'Alignment failed'}
$key=Join-Path $build 'debug.jks'
if(-not (Test-Path $key)){& "$jdk/bin/keytool.exe" -genkeypair -keystore $key -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=Android Debug,O=Android,C=US'}
& "$bt/apksigner.bat" sign --ks $key --ks-key-alias androiddebugkey --ks-pass pass:android --min-sdk-version 3 --v1-signing-enabled true $apk
if($LASTEXITCODE -ne 0){throw 'Signing failed'}
& "$bt/apksigner.bat" verify --min-sdk-version 3 $apk
if($LASTEXITCODE -ne 0){throw 'Verification failed'}
& "$bt/aapt2.exe" dump badging $apk | Select-Object -First 3
Get-Item $apk | Select-Object Name,Length
