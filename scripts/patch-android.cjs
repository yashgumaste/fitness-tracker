// Runs after `npx cap add android`: permissions, keyboard behaviour, backup, version number,
// and the notification icon. Safe to run more than once.
const fs=require('fs'),path=require('path');
const app=path.join(__dirname,'..','android','app');
const run=parseInt(process.env.RUN||'1',10);

// Manifest
const mf=path.join(app,'src/main/AndroidManifest.xml');
let m=fs.readFileSync(mf,'utf8');
for(const perm of ['android.permission.POST_NOTIFICATIONS','android.permission.RECEIVE_BOOT_COMPLETED','android.permission.WAKE_LOCK']){
  if(!m.includes(perm))m=m.replace('</manifest>',`    <uses-permission android:name="${perm}" />\n</manifest>`);
}
if(!/windowSoftInputMode/.test(m))m=m.replace(/<activity\b/,'<activity\n            android:windowSoftInputMode="adjustResize"');
if(!/android:allowBackup/.test(m))m=m.replace(/<application\b/,'<application\n        android:allowBackup="true"');
else m=m.replace(/android:allowBackup="false"/,'android:allowBackup="true"');
// Accent-coloured launcher icons (activity aliases)
const icons=require('./app-icons.cjs');
m=icons.manifest(m,'com.yashgumaste.fitnesstracker');
if(/<activity[^>]*MainActivity[\s\S]*?category\.LAUNCHER[\s\S]*?<\/activity>/.test(m))throw new Error('MainActivity still has the launcher filter — icon aliases would duplicate the app icon');
// Health Connect: permissions, the screens Health Connect opens to explain them, and package visibility
const HC=['READ_STEPS','WRITE_NUTRITION','WRITE_HYDRATION','WRITE_EXERCISE','WRITE_WEIGHT','WRITE_SLEEP'];
for(const h of HC){const perm='android.permission.health.'+h;
  if(!m.includes(perm))m=m.replace('</manifest>',`    <uses-permission android:name="${perm}" />\n</manifest>`);}
if(!m.includes('com.google.android.apps.healthdata'))
  m=m.replace('</manifest>','    <queries>\n        <package android:name="com.google.android.apps.healthdata" />\n    </queries>\n</manifest>');
if(!m.includes('ACTION_SHOW_PERMISSIONS_RATIONALE'))
  m=m.replace(/(<activity\b[^>]*MainActivity[\s\S]*?>)/,`$1
            <intent-filter>
                <action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" />
            </intent-filter>`);
if(!m.includes('ViewPermissionUsageActivity'))
  m=m.replace('</application>',`        <activity-alias
            android:name="ViewPermissionUsageActivity"
            android:exported="true"
            android:targetActivity=".MainActivity"
            android:permission="android.permission.START_VIEW_PERMISSION_USAGE">
            <intent-filter>
                <action android:name="android.intent.action.VIEW_PERMISSION_USAGE" />
                <category android:name="android.intent.category.HEALTH_PERMISSIONS" />
            </intent-filter>
        </activity-alias>
    </application>`);
fs.writeFileSync(mf,m);
icons.write(path.join(app,'src/main/res'));

// Kotlin + Health Connect library (needs Android 8.0+, minSdk 26)
const vars=path.join(app,'..','variables.gradle');
if(fs.existsSync(vars)){let v=fs.readFileSync(vars,'utf8');
  v=v.replace(/minSdkVersion\s*=\s*(\d+)/,(s,n)=>`minSdkVersion = ${Math.max(26,+n)}`);fs.writeFileSync(vars,v);}
const rootGradle=path.join(app,'..','build.gradle');
let rg=fs.readFileSync(rootGradle,'utf8');
if(!rg.includes('kotlin-gradle-plugin')){
  rg=rg.replace(/(classpath ['"]com\.android\.tools\.build:gradle:[^'"]+['"])/,`$1\n        classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.20'`);
  if(!rg.includes('kotlin-gradle-plugin'))throw new Error('Could not add the Kotlin plugin to android/build.gradle');
  fs.writeFileSync(rootGradle,rg);
}
const appGradle=path.join(app,'build.gradle');
let ag=fs.readFileSync(appGradle,'utf8');
if(!ag.includes('kotlin-android')){
  ag=ag.replace(/apply plugin: ['"]com\.android\.application['"]/,m0=>m0+`\napply plugin: 'kotlin-android'`);
  ag+=`
dependencies {
    implementation "androidx.health.connect:connect-client:1.1.0"
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2"
}
tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).configureEach {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(android.compileOptions.targetCompatibility.toString())) }
}
`;
  if(!ag.includes("kotlin-android"))throw new Error('Could not apply Kotlin in app/build.gradle');
  fs.writeFileSync(appGradle,ag);
}

// Register the AppIcon plugin in MainActivity
const javaDir=path.join(app,'src/main/java/com/yashgumaste/fitnesstracker');
fs.copyFileSync(path.join(__dirname,'..','native','AppIconPlugin.java'),path.join(javaDir,'AppIconPlugin.java'));
fs.copyFileSync(path.join(__dirname,'..','native','HealthConnectPlugin.kt'),path.join(javaDir,'HealthConnectPlugin.kt'));
const ma=path.join(javaDir,'MainActivity.java');
let j=fs.readFileSync(ma,'utf8');
if(!j.includes('AppIconPlugin')){
  j=j.replace(/public class MainActivity extends BridgeActivity\s*\{\s*\}/,
`public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(AppIconPlugin.class);
        registerPlugin(HealthConnectPlugin.class);
        super.onCreate(savedInstanceState);
    }
}`);
  if(!j.includes('AppIconPlugin'))throw new Error('Could not register AppIconPlugin in MainActivity:\n'+j);
  fs.writeFileSync(ma,j);
}

// Version: 1.<build number>
for(const f of ['build.gradle','build.gradle.kts']){
  const p=path.join(app,f);if(!fs.existsSync(p))continue;
  let g=fs.readFileSync(p,'utf8');
  g=g.replace(/versionCode\s*(=\s*)?\d+/,(s,eq)=>`versionCode ${eq||''}${run}`).replace(/versionName\s*(=\s*)?"[^"]*"/,(s,eq)=>`versionName ${eq||''}"1.${run}"`);
  fs.writeFileSync(p,g);
}

// Notification icon (white water drop)
const dr=path.join(app,'src/main/res/drawable');fs.mkdirSync(dr,{recursive:true});
fs.writeFileSync(path.join(dr,'ic_stat_water.xml'),`<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
  <path android:fillColor="#FFFFFFFF" android:pathData="M12,2.2C12,2.2 5.5,9.6 5.5,14.4C5.5,18.1 8.4,21 12,21C15.6,21 18.5,18.1 18.5,14.4C18.5,9.6 12,2.2 12,2.2Z"/>
</vector>
`);
console.log('android patched · versionCode',run);
