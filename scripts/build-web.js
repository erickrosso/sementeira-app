// Copia o jogo (index.html) para a pasta www, que o Capacitor empacota no app.
const fs = require('fs');
fs.mkdirSync('www', { recursive: true });
fs.copyFileSync('index.html', 'www/index.html');
console.log('index.html copiado para www/');
