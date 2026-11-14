// Launcher icons in every accent colour. The app switches between them (see AppIconPlugin.java) so the
// home-screen icon matches the accent picked in Settings. Each icon is an adaptive icon: black background,
// the logo (apple with a bolt cut out) in the accent colour, plus a monochrome layer for Android's themed icons.
const fs=require('fs'),path=require('path');

const ACCENTS=[['cyan','#00FFFF'],['teal','#00E5B0'],['mint','#5CF2A6'],['lime','#B6F36A'],['lemon','#FFE04A'],['amber','#FFC247'],
  ['coral','#FF8A65'],['rose','#FF6F91'],['pink','#FF6FD1'],['orchid','#D78BFF'],['violet','#B9A2FF'],['sky','#7AB8FF'],
  // a full colour wheel too, so a custom accent (Make your own colour) gets an icon that really matches
  ...Array.from({length:24},(_,i)=>{const h=i*15,f=n=>{const k=(n+h/30)%12;return Math.round(255*(.5-.5*Math.max(-1,Math.min(k-3,9-k,1))));};
    return['h'+String(h).padStart(3,'0'),'#'+[f(0),f(8),f(4)].map(v=>v.toString(16).padStart(2,'0')).join('').toUpperCase()];})];

// The logo: an apple with a lightning bolt cut out (food as energy), and a separate leaf. One colour, no overlaps,
// so it stays readable down to the status bar. Drawn on a 24×24 grid.
const APPLE='M12 7.6C10.3 6.3 7.6 6.1 5.8 7.7C3.6 9.7 3.6 13.9 5.4 17.1C6.8 19.7 8.8 21.6 10.6 21.2C11.3 21 11.6 20.7 12 20.7C12.4 20.7 12.7 21 13.4 21.2C15.2 21.6 17.2 19.7 18.6 17.1C20.4 13.9 20.4 9.7 18.2 7.7C16.4 6.1 13.7 6.3 12 7.6Z';
const BOLT='M13.6 8.6L8.2 14.9H11.5L10.4 19.6L15.8 13.1H12.5Z';
const LEAF='M12.9 5.6C12.9 3.4 14.5 1.9 16.8 1.9C16.8 4.1 15.2 5.6 12.9 5.6Z';
const LOGO={apple:APPLE,bolt:BOLT,leaf:LEAF,body:APPLE+BOLT};   // body is drawn even-odd, so the bolt is a hole
// in the 108×108 adaptive-icon canvas the logo is scaled to sit inside the 66-unit safe zone
const glyph=fill=>`  <group android:scaleX="2.5" android:scaleY="2.5" android:translateX="24" android:translateY="24.6">
    <path android:fillColor="${fill}" android:fillType="evenOdd" android:pathData="${LOGO.body}"/>
    <path android:fillColor="${fill}" android:pathData="${LOGO.leaf}"/>
  </group>
`;
const vec=paths=>`<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n${paths}</vector>\n`;

function write(res){
  const dr=path.join(res,'drawable'),any=path.join(res,'mipmap-anydpi-v26'),mm=path.join(res,'mipmap');
  for(const d of [dr,any,mm,path.join(res,'values')])fs.mkdirSync(d,{recursive:true});
  fs.writeFileSync(path.join(res,'values','icon_colors.xml'),'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n  <color name="ic_bg_black">#000000</color>\n</resources>\n');
  // themed-icon silhouette (Android tints it to match the wallpaper)
  fs.writeFileSync(path.join(dr,'ic_mono.xml'),vec(glyph('#FFFFFFFF')));
  // notification icon: the logo in white, filling the status-bar slot
  fs.writeFileSync(path.join(dr,'ic_stat_app.xml'),`<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
  <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="${LOGO.body}"/>
  <path android:fillColor="#FFFFFFFF" android:pathData="${LOGO.leaf}"/>
</vector>
`);
  for(const [name,hex] of ACCENTS){
    fs.writeFileSync(path.join(dr,`ic_fg_${name}.xml`),vec(glyph(hex)));
    fs.writeFileSync(path.join(any,`ic_launcher_${name}.xml`),`<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
  <background android:drawable="@color/ic_bg_black"/>
  <foreground android:drawable="@drawable/ic_fg_${name}"/>
  <monochrome android:drawable="@drawable/ic_mono"/>
</adaptive-icon>
`);
    // older Android (before adaptive icons)
    fs.writeFileSync(path.join(mm,`ic_launcher_${name}.xml`),`<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
  <item android:drawable="@color/ic_bg_black"/>
  <item android:drawable="@drawable/ic_fg_${name}"/>
</layer-list>
`);
  }
}

// Manifest: the launcher entry moves from MainActivity to one alias per colour (only cyan enabled at first)
function manifest(m,pkg){
  if(m.includes('.Icon_cyan'))return m;
  m=m.replace(/<intent-filter>\s*<action android:name="android\.intent\.action\.MAIN"\s*\/>\s*<category android:name="android\.intent\.category\.LAUNCHER"\s*\/>\s*<\/intent-filter>/,'');
  const aliases=ACCENTS.map(([name])=>`        <activity-alias
            android:name="${pkg}.Icon_${name}"
            android:targetActivity=".MainActivity"
            android:enabled="${name==='cyan'}"
            android:exported="true"
            android:icon="@mipmap/ic_launcher_${name}"
            android:roundIcon="@mipmap/ic_launcher_${name}"
            android:label="@string/app_name">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity-alias>`).join('\n');
  return m.replace('</application>',aliases+'\n    </application>');
}

module.exports={ACCENTS,LOGO,write,manifest};
