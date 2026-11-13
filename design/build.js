// Inlines icon/ assets into mockup.src.html -> mockup.html (self-contained, previewable anywhere).
const fs = require('fs'), d = __dirname + '/';
let h = fs.readFileSync(d + 'mockup.src.html', 'utf8');
const uri = n => 'data:image/svg+xml;base64,' + fs.readFileSync(d + 'icon/' + n).toString('base64');
const logo = uri('nether-logo.svg');
h = h.split('src="icon/nether-logo.svg"').join('src="' + logo + '"').split('src="icon/nether-block.svg"').join('src="' + uri('nether-block.svg') + '"');
h = h.replace('<link href="https://fonts', '<link rel="icon" href="' + logo + '">\n<link href="https://fonts');
h = h.replace('<script src="icon/tex-nether.js"></script>', '<script>' + fs.readFileSync(d + 'icon/tex-nether.js', 'utf8') + '</script>');
fs.writeFileSync(d + 'mockup.html', h);
console.log('built', h.length);
