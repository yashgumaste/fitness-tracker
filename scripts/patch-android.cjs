// Runs after `npx cap add android`: permissions, keyboard behaviour, backup, version number,
// and the notification icon. Safe to run more than once.
const fs=require('fs'),path=require('path');
const app=path.join(__dirname,'..','android','app');
const run=parseInt(process.env.RUN||'1',10);

// Manifest
const mf=path.join(app,'src/main/AndroidManifest.xml');
let m=fs.readFileSync(mf,'utf8');
for(const perm of ['android.permission.POST_NOTIFICATIONS','android.permission.RECEIVE_BOOT_COMPLETED','android.permission.WAKE_LOCK','android.permission.REQUEST_INSTALL_PACKAGES','android.permission.ACTIVITY_RECOGNITION',
  'android.permission.ACCESS_FINE_LOCATION','android.permission.ACCESS_COARSE_LOCATION','android.permission.FOREGROUND_SERVICE','android.permission.FOREGROUND_SERVICE_LOCATION']){
  if(!m.includes(perm))m=m.replace('</manifest>',`    <uses-permission android:name="${perm}" />\n</manifest>`);
}
if(!m.includes('android.hardware.sensor.stepcounter'))m=m.replace('</manifest>','    <uses-feature android:name="android.hardware.sensor.stepcounter" android:required="false" />\n</manifest>');
if(!/windowSoftInputMode/.test(m))m=m.replace(/<activity\b/,'<activity\n            android:windowSoftInputMode="adjustResize"');
if(!/android:allowBackup/.test(m))m=m.replace(/<application\b/,'<application\n        android:allowBackup="true"');
else m=m.replace(/android:allowBackup="false"/,'android:allowBackup="true"');
// Accent-coloured launcher icons (activity aliases)
const icons=require('./app-icons.cjs');
m=icons.manifest(m,'com.yashgumaste.fitnesstracker');
if(/<activity[^>]*MainActivity[\s\S]*?category\.LAUNCHER[\s\S]*?<\/activity>/.test(m))throw new Error('MainActivity still has the launcher filter — icon aliases would duplicate the app icon');
// Health Connect: permissions, the screens Health Connect opens to explain them, and package visibility
const HC=['READ_STEPS','WRITE_NUTRITION','WRITE_HYDRATION','WRITE_EXERCISE','WRITE_WEIGHT','WRITE_SLEEP','WRITE_STEPS','WRITE_DISTANCE','WRITE_EXERCISE_ROUTE'];
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
    implementation "androidx.work:work-runtime-ktx:2.10.1"
    implementation "com.google.android.gms:play-services-auth:21.3.0"
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
// AppIcon plugin: its list of icon names comes from app-icons.cjs, so the two always match
fs.writeFileSync(path.join(javaDir,'AppIconPlugin.java'),fs.readFileSync(path.join(__dirname,'..','native','AppIconPlugin.java'),'utf8')
  .replace(/static final String\[\] NAMES = \{[^}]*\};/,`static final String[] NAMES = {${icons.ACCENTS.map(([n])=>JSON.stringify(n)).join(',')}};`));
fs.copyFileSync(path.join(__dirname,'..','native','HealthConnectPlugin.kt'),path.join(javaDir,'HealthConnectPlugin.kt'));
fs.copyFileSync(path.join(__dirname,'..','native','AppUpdatePlugin.java'),path.join(javaDir,'AppUpdatePlugin.java'));
fs.copyFileSync(path.join(__dirname,'..','native','MoveReminderPlugin.kt'),path.join(javaDir,'MoveReminderPlugin.kt'));
fs.copyFileSync(path.join(__dirname,'..','native','WaterReminderPlugin.kt'),path.join(javaDir,'WaterReminderPlugin.kt'));
fs.copyFileSync(path.join(__dirname,'..','native','WalkTrackerPlugin.kt'),path.join(javaDir,'WalkTrackerPlugin.kt'));
fs.copyFileSync(path.join(__dirname,'..','native','DriveBackupPlugin.kt'),path.join(javaDir,'DriveBackupPlugin.kt'));
fs.copyFileSync(path.join(__dirname,'..','native','LogReminderPlugin.kt'),path.join(javaDir,'LogReminderPlugin.kt'));
const ma=path.join(javaDir,'MainActivity.java');
let j=fs.readFileSync(ma,'utf8');
if(!j.includes('AppIconPlugin')){
  j=j.replace(/public class MainActivity extends BridgeActivity\s*\{\s*\}/,
`public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(AppIconPlugin.class);
        registerPlugin(HealthConnectPlugin.class);
        registerPlugin(AppUpdatePlugin.class);
        registerPlugin(MoveReminderPlugin.class);
        registerPlugin(WaterReminderPlugin.class);
        registerPlugin(WalkTrackerPlugin.class);
        registerPlugin(DriveBackupPlugin.class);
        registerPlugin(LogReminderPlugin.class);
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
// Notification icon for move reminders (white walking figure)
fs.writeFileSync(path.join(dr,'ic_stat_walk.xml'),`<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
  <path android:fillColor="#FFFFFFFF" android:pathData="M13.5,5.5C14.6,5.5 15.5,4.6 15.5,3.5S14.6,1.5 13.5,1.5 11.5,2.4 11.5,3.5 12.4,5.5 13.5,5.5ZM9.8,8.9L7,23H9.1L10.9,15L13,17V23H15V15.5L12.9,13.5L13.5,10.5C14.8,12 16.8,13 19,13V11C17.1,11 15.5,10 14.7,8.6L13.7,7C13.3,6.4 12.7,6 12,6C11.7,6 11.5,6.1 11.2,6.1L6,8.3V13H8V9.6L9.8,8.9Z"/>
</vector>
`);
// Pictures shown inside notifications (the round image on the right): water and walking
const DROP='M12,2.2C12,2.2 5.5,9.6 5.5,14.4C5.5,18.1 8.4,21 12,21C15.6,21 18.5,18.1 18.5,14.4C18.5,9.6 12,2.2 12,2.2Z';
const WALKER='M13.5,5.5C14.6,5.5 15.5,4.6 15.5,3.5S14.6,1.5 13.5,1.5 11.5,2.4 11.5,3.5 12.4,5.5 13.5,5.5ZM9.8,8.9L7,23H9.1L10.9,15L13,17V23H15V15.5L12.9,13.5L13.5,10.5C14.8,12 16.8,13 19,13V11C17.1,11 15.5,10 14.7,8.6L13.7,7C13.3,6.4 12.7,6 12,6C11.7,6 11.5,6.1 11.2,6.1L6,8.3V13H8V9.6L9.8,8.9Z';
const big=(bg,fg,d)=>`<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="96dp" android:height="96dp" android:viewportWidth="96" android:viewportHeight="96">
  <path android:fillColor="${bg}" android:pathData="M48,0A48,48 0,1 1,48,96A48,48 0,1 1,48,0Z"/>
  <group android:translateX="19.2" android:translateY="19.2" android:scaleX="2.4" android:scaleY="2.4">
    <path android:fillColor="${fg}" android:pathData="${d}"/>
  </group>
</vector>
`;
fs.writeFileSync(path.join(dr,'ic_large_water.xml'),big('#FF4FC3F7','#FFFFFFFF',DROP));
fs.writeFileSync(path.join(dr,'ic_large_walk.xml'),big('#FF5CF2A6','#FF00391F',WALKER));
// ...and for logging reminders: a moon (sleep) and a fork and knife (meals)
const MOON='M12.34,2.02C6.59,1.82 2,6.42 2,12c0,5.52 4.48,10 10,10c3.71,0 6.93,-2.02 8.66,-5.02C13.15,16.73 8.57,8.55 12.34,2.02z';
const MEAL='M11,9H9V2H7v7H5V2H3v7c0,2.12 1.66,3.84 3.75,3.97V22h2.5v-9.03C11.34,12.84 13,11.12 13,9V2h-2V9zM16,6v8h2.5v8H21V2C18.24,2 16,4.24 16,6z';
fs.writeFileSync(path.join(dr,'ic_large_sleep.xml'),big('#FFB39DDB','#FF21005D',MOON));
fs.writeFileSync(path.join(dr,'ic_large_meal.xml'),big('#FFFFB74D','#FF3E2400',MEAL));

// Water reminders are posted by WaterReminderPlugin: alarm, notification-button and restart receivers
m=fs.readFileSync(mf,'utf8');
if(!m.includes('WaterAlarmReceiver')){
  m=m.replace('</application>',`        <service android:name=".WalkService" android:exported="false" android:foregroundServiceType="location" />
        <receiver android:name=".WaterAlarmReceiver" android:exported="false" />
        <receiver android:name=".WaterActionReceiver" android:exported="false" />
        <receiver android:name=".WaterBootReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>
    </application>`);
  fs.writeFileSync(mf,m);
}
// Logging reminders (sleep, meals): alarm, "Later" button and restart receivers
m=fs.readFileSync(mf,'utf8');
if(!m.includes('LogAlarmReceiver')){
  m=m.replace('</application>',`        <receiver android:name=".LogAlarmReceiver" android:exported="false" />
        <receiver android:name=".LogActionReceiver" android:exported="false" />
        <receiver android:name=".LogBootReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>
    </application>`);
  fs.writeFileSync(mf,m);
}
console.log('android patched · versionCode',run);
