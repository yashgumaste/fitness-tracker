// Builds www/ from src/: compiles the app's JSX with esbuild and copies React + fonts locally,
// so the installed app needs no internet except for the AI.
//   node scripts/build-web.cjs            (CI: uses node_modules)
//   VENDOR_DIR=/path node scripts/build-web.cjs   (local test: copy vendor files from a folder instead)
const fs=require('fs'),path=require('path');
const root=path.join(__dirname,'..'),www=path.join(root,'www'),vendor=path.join(www,'vendor');
const version=process.env.APP_VERSION||'dev';
const esbuild=require(process.env.ESBUILD_PATH||'esbuild');

fs.rmSync(www,{recursive:true,force:true});fs.mkdirSync(vendor,{recursive:true});

// 1. App page: precompile the <script type="text/babel"> block
let html=fs.readFileSync(path.join(root,'src/app.html'),'utf8');
html=html.replace(/<script src="[^"]*babel[^"]*"><\/script>\n?/g,'')
         .replace(/<script>const _cw=console\.warn[^<]*<\/script>\n?/,'');
const tag='<script type="text/babel">';const i=html.indexOf(tag);const j=html.indexOf('</script>',i);
if(i<0||j<0)throw new Error('JSX block not found');
const jsx=html.slice(i+tag.length,j);
const out=esbuild.transformSync(jsx,{loader:'jsx',minify:true,target:'es2019',legalComments:'none'});
html=html.slice(0,i)+'<script>'+out.code+'</script>'+html.slice(j+9);
// What's new: the changelog travels inside the app, so the history works offline
const cl=fs.readFileSync(path.join(root,'changelog.json'),'utf8');JSON.parse(cl);
html=html.replace('</head>',()=>`<script>window.LM_CHANGELOG=${cl.replace(/</g,'\\u003c')};</script>\n</head>`);
fs.writeFileSync(path.join(www,'index.html'),html);
fs.writeFileSync(path.join(www,'native.js'),fs.readFileSync(path.join(root,'src/native.js'),'utf8').replace(/__APP_VERSION__/g,version).replace('__ICON_COLORS__',JSON.stringify(require('./app-icons.cjs').ACCENTS)).replace('__GEMINI_FREE_KEY__',process.env.GEMINI_FREE_KEY||'__GEMINI_FREE_KEY__'));

// 2. Vendor: React UMD builds
const nm=p=>path.join(root,'node_modules',p);
if(process.env.VENDOR_DIR){
  for(const f of fs.readdirSync(process.env.VENDOR_DIR))fs.copyFileSync(path.join(process.env.VENDOR_DIR,f),path.join(vendor,f));
}else{
  fs.copyFileSync(nm('react/umd/react.production.min.js'),path.join(vendor,'react.production.min.js'));
  fs.copyFileSync(nm('react-dom/umd/react-dom.production.min.js'),path.join(vendor,'react-dom.production.min.js'));
  // 3. Fonts: Roboto Flex + Roboto Serif variable fonts (renamed to the family names the CSS uses)
  let css='';
  for(const [pkg,from,to] of [['@fontsource-variable/roboto-flex','Roboto Flex Variable','Roboto Flex'],['@fontsource-variable/roboto-serif','Roboto Serif Variable','Roboto Serif']]){
    const dir=nm(pkg);if(!fs.existsSync(dir)){console.warn('missing',pkg);continue;}
    const cssFile=['full.css','index.css'].map(f=>path.join(dir,f)).find(f=>fs.existsSync(f));
    const slug=pkg.split('/')[1];const dest=path.join(vendor,slug);fs.mkdirSync(dest,{recursive:true});
    // only latin + latin-ext + greek faces (keeps the app small)
    let c=fs.readFileSync(cssFile,'utf8');
    const blocks=c.split('}').filter(b=>b.includes('@font-face')).map(b=>b+'}').filter(b=>/latin|greek/.test(b)&&!/cyrillic|vietnamese/.test(b));
    for(const b of blocks){
      const m=b.match(/url\(\.\/files\/([^)]+)\)/g)||[];
      for(const u of m){const f=u.slice(12,-1);const srcF=path.join(dir,'files',f);if(fs.existsSync(srcF))fs.copyFileSync(srcF,path.join(dest,f));}
      css+=b.replace(/url\(\.\/files\//g,`url(./${slug}/`).replace(new RegExp(`'${from}'|"${from}"`,'g'),`'${to}'`)+'\n';
    }
  }
  fs.writeFileSync(path.join(vendor,'fonts.css'),css);
}
if(!fs.existsSync(path.join(vendor,'fonts.css')))fs.writeFileSync(path.join(vendor,'fonts.css'),'');
console.log('www built · version',version,'·',Math.round(html.length/1024),'KB');
