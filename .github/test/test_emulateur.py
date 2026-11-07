#!/usr/bin/env python3
"""
Test automatique du launcher sur un émulateur Android 1280x720 (lancé par .github/workflows/verifier.yml).

Il installe l'APK de test, mesure le démarrage, fait tourner la voiture au doigt, ouvre les portières,
passe sur tous les écrans, simule la conduite avec la vraie carte de Belgique, teste la bulle de volume,
puis fouille le journal d'Android à la recherche du moindre plantage. Tout est rangé dans le dossier de sortie.

Utilisation : test_emulateur.py <apk> <dossier-sortie> <api> [belgium.map]
"""
import os
import re
import subprocess
import sys
import time

APK, OUT, API = sys.argv[1], sys.argv[2], int(sys.argv[3])
MAP = sys.argv[4] if len(sys.argv) > 4 else ''
PKG = 'be.pa.launcher208'
ACT = PKG + '/.HomeActivity'
os.makedirs(OUT, exist_ok=True)
RES = open(os.path.join(OUT, 'resume.txt'), 'w', encoding='utf-8')


def log(*a):
    s = ' '.join(str(x) for x in a)
    print(s, flush=True)
    RES.write(s + '\n')
    RES.flush()


def adb(cmd, timeout=180):
    try:
        r = subprocess.run('adb ' + cmd, shell=True, capture_output=True, text=True, timeout=timeout,
                           encoding='utf-8', errors='replace')
        return (r.stdout or '') + (r.stderr or '')
    except subprocess.TimeoutExpired:
        return 'DELAI DEPASSE'


def shot(name, wait=1.5):
    time.sleep(wait)
    try:
        png = subprocess.run('adb exec-out screencap -p', shell=True, capture_output=True, timeout=60).stdout
        with open(os.path.join(OUT, name + '.png'), 'wb') as f:
            f.write(png)
    except Exception as e:
        log('  capture impossible', name, e)


def hook(**kw):
    args = []
    for k, v in kw.items():
        args.append(('--es test_%s %s' % (k, v if v else '""')) if isinstance(v, str) else ('--ei test_%s %d' % (k, v)))
    adb('shell am start -n %s %s' % (ACT, ' '.join(args)))


def last(s):
    s = s.strip().splitlines()
    return s[-1] if s else '(rien)'


def pid():
    return adb('shell pidof ' + PKG).strip()


def swipe(x1, y1, x2, y2, ms):
    adb('shell input swipe %d %d %d %d %d' % (x1, y1, x2, y2, ms))


def gfx_reset():
    adb('shell dumpsys gfxinfo %s reset' % PKG)


def gfx(label):
    t = adb('shell dumpsys gfxinfo ' + PKG)
    with open(os.path.join(OUT, 'fluidite_' + re.sub(r'\W+', '_', label) + '.txt'), 'w', encoding='utf-8') as f:
        f.write(t)

    def g(p):
        m = re.search(p, t)
        return m.group(1) if m else '?'
    log('  %s : %s images, saccadées %s, temps médian %s ms, 90 %% sous %s ms, 99 %% sous %s ms' % (
        label, g(r'Total frames rendered: (\d+)'), g(r'Janky frames: (\d+ \([\d.]+%\))'),
        g(r'50th percentile: (\d+)ms'), g(r'90th percentile: (\d+)ms'), g(r'99th percentile: (\d+)ms')))


def memory(label):
    t = adb('shell dumpsys meminfo ' + PKG)
    with open(os.path.join(OUT, 'memoire_' + re.sub(r'\W+', '_', label) + '.txt'), 'w', encoding='utf-8') as f:
        f.write(t)
    m = re.search(r'TOTAL PSS:\s+(\d+)', t) or re.search(r'\n\s+TOTAL\s+(\d+)', t)
    gr = re.search(r'Graphics:\s+(\d+)', t)
    nat = re.search(r'Native Heap:\s+(\d+)', t)
    java = re.search(r'Java Heap:\s+(\d+)', t)
    mb = lambda x: ('%.0f Mo' % (int(x.group(1)) / 1024)) if x else '?'
    log('  mémoire %s : total %s (Java %s, natif/images %s, graphique %s)' % (label, mb(m), mb(java), mb(nat), mb(gr)))


def car_bounds():
    adb('logcat -c')
    hook(info=1)
    time.sleep(1.5)
    t = adb('logcat -d -s Test208:I')
    m = re.findall(r'voiture (\d+) (\d+) (\d+) (\d+)', t)
    if m:
        x, y, w, h = (int(v) for v in m[-1])
        return x, y, w, h
    log('  (position de la voiture introuvable, valeur par défaut)')
    return 260, 200, 720, 360


log('=== Test du launcher sur émulateur Android (API %d) ===' % API)
log('Android', adb('shell getprop ro.build.version.release').strip())
log('Écran :', adb('shell wm size').strip().replace('\n', ' | '), '|', adb('shell wm density').strip().replace('\n', ' | '))

# ---------- Installation et autorisations ----------
log('\n--- Installation ---')
adb('logcat -G 16M')
log(' ', last(adb('install -r -g "%s"' % APK, 300)))
for op in ('SYSTEM_ALERT_WINDOW', 'GET_USAGE_STATS'):
    adb('shell appops set %s %s allow' % (PKG, op))
adb('shell cmd notification allow_listener %s/%s.NotifListener' % (PKG, PKG))
log(' ', 'écran d\'accueil par défaut :', adb('shell cmd package set-home-activity ' + ACT).strip() or 'ok')
adb('shell settings put system screen_off_timeout 1800000')
adb('shell svc power stayon true')
adb('emu geo fix 3.9517 50.4542')  # Grand-Place de Mons

if MAP and os.path.exists(MAP):
    d = '/sdcard/Android/data/%s/files' % PKG
    adb('shell mkdir -p ' + d)
    r = adb('push "%s" %s/belgium.map' % (MAP, d), 900)
    log('  carte de Belgique copiée :', last(r))
    log('  ', adb('shell ls -l %s/belgium.map' % d).strip())

# ---------- Démarrage ----------
log('\n--- Démarrage à froid (appli complètement fermée), 3 essais ---')
times = []
for k in range(3):
    adb('shell am start -W -a android.settings.SETTINGS')
    time.sleep(1)
    adb('shell am force-stop ' + PKG)
    time.sleep(1)
    t = adb('shell am start -W -n ' + ACT)
    m = re.search(r'TotalTime: (\d+)', t)
    times.append(int(m.group(1)) if m else -1)
    time.sleep(4)
log('  temps jusqu\'à la première image : %s ms' % ', '.join(map(str, times)))
hook(theme=1)  # jour pour les captures
time.sleep(2)
p0 = pid()
log('  numéro du processus :', p0)
shot('01-accueil', 3)
memory('accueil (72 vues préchargées)')

# ---------- Voiture 360° ----------
log('\n--- Voiture 360° au doigt ---')
x, y, w, h = car_bounds()
cx, cy = x + w // 2, y + h // 2
log('  voiture à l\'écran : x=%d y=%d, %dx%d' % (x, y, w, h))
gfx_reset()
for k in range(2):
    swipe(cx - w // 3, cy, cx + w // 3, cy, 1400)   # glisser lentement vers la droite
    time.sleep(1)
    swipe(cx + w // 3, cy, cx - w // 3, cy, 1400)   # puis vers la gauche
    time.sleep(1)
swipe(cx - w // 4, cy, cx + w // 4, cy, 120)        # lancer d'un coup sec (élan)
time.sleep(2.5)
swipe(cx + w // 4, cy, cx - w // 4, cy, 120)
time.sleep(2.5)
gfx('rotation de la voiture')
swipe(cx - w // 3, cy, cx, cy, 700)
shot('02-voiture-tournee', 1.5)

# ---------- Portières ----------
log('\n--- Portières (simulation CAN) ---')
gfx_reset()
hook(porte=1)
shot('03-porte-conducteur', 2.5)
hook(porte=0)
shot('04-porte-fermee-retour', 2.5)
hook(porte=16)
shot('05-coffre', 2.5)
hook(porte=2)
shot('06-porte-passager', 2.5)
hook(porte=0)
time.sleep(2.5)
gfx('portières (rotation automatique)')

# ---------- Écrans ----------
log('\n--- Tous les écrans ---')
hook(onglet=1); shot('07-trajets')
hook(onglet=2); shot('08-entretien')
hook(onglet=0)
gfx_reset()
hook(ecran=1); shot('09-applications', 2.5)
swipe(700, 550, 700, 200, 400); shot('09b-applications-defilement', 1.5)
hook(ecran=2); shot('10-reglages')
swipe(700, 600, 700, 150, 500); shot('10b-reglages-bas', 1.5)
swipe(700, 600, 700, 150, 500); shot('10c-reglages-fin', 1.5)
hook(ecran=3); shot('11-connexions')
hook(ecran=4); shot('12-mode-test', 2.5)
swipe(700, 600, 700, 150, 500); shot('12b-mode-test-bas', 1.5)
gfx('changement d\'écrans et défilement')
hook(ecran=0)
time.sleep(1)

# ---------- Mode conduite avec la carte ----------
log('\n--- Mode conduite (50 km/h simulés, GPS qui avance dans Mons) ---')
hook(vitesse=50)
time.sleep(3)
gfx_reset()
lat, lon = 50.4542, 3.9517
for k in range(20):                        # environ 14 m par seconde vers l'est
    lon += 0.000197
    adb('emu geo fix %.6f %.6f' % (lon, lat))
    time.sleep(1)
gfx('conduite avec minimap')
shot('13-conduite', 0.5)
memory('conduite avec la carte')
hook(vitesse=-1)
shot('14-retour-accueil', 2)

# ---------- Nuit ----------
log('\n--- Mode nuit ---')
hook(theme=2)
shot('15-nuit-accueil', 2.5)
hook(vitesse=50)
shot('16-nuit-conduite', 4)
hook(vitesse=-1)
hook(ecran=2); shot('17-nuit-reglages')
hook(ecran=0)
hook(theme=1)
time.sleep(2)

# ---------- Bulle de volume par-dessus « CarPlay » ----------
log('\n--- Bulle de volume (une autre appli joue le rôle de S-Play) ---')
hook(carplay='com.android.settings')
time.sleep(1)
adb('shell am start -a android.settings.SETTINGS')
shot('18-bulle-sur-carplay', 4)
sz = adb('shell wm size')
m = re.findall(r'(\d+)x(\d+)', sz)
W, H = (int(m[-1][0]), int(m[-1][1])) if m else (1280, 720)
k = min(W / 1280.0, H / 720.0)
bx, by = int(W - 14 * k - 30 * k), int(H / 2)
adb('shell input tap %d %d' % (bx, by))
shot('19-bulle-ouverte', 1.2)
adb('shell input swipe %d %d %d %d 600' % (bx, by, bx - 300, by - 150))  # (déjà refermée ou non : on vérifie juste qu'il n'y a pas de plantage)
time.sleep(4)
shot('20-bulle-deplacee', 0.5)
adb('shell input keyevent 3')  # bouton accueil
time.sleep(2)
hook(carplay='')
shot('21-retour-accueil-bouton', 1)

# ---------- Rotation d'écran / reconstruction ----------
hook(theme=2); time.sleep(2); hook(theme=1); time.sleep(2)

# ---------- Stress : 1500 touchers au hasard dans l'appli ----------
log('\n--- Stress : 1500 touchers au hasard (monkey) ---')
r = adb('shell monkey -p %s -s 208 --throttle 60 --pct-syskeys 0 --pct-appswitch 0 --pct-anyevent 0 -v 1500' % PKG, 600)
with open(os.path.join(OUT, 'monkey.txt'), 'w', encoding='utf-8') as f:
    f.write(r)
log('  ', 'terminé sans plantage' if 'Monkey finished' in r else 'INTERROMPU : voir monkey.txt')
time.sleep(2)
adb('shell am start -n ' + ACT)
shot('22-apres-stress', 2)

# ---------- Bilan du journal ----------
log('\n--- Journal Android ---')
lc = adb('logcat -d -v threadtime -b main -b system -b crash', 300)
with open(os.path.join(OUT, 'journal.txt'), 'w', encoding='utf-8') as f:
    f.write(lc)
lines = lc.splitlines()
p1 = pid()
log('  processus au début :', p0, '| à la fin :', p1, '(identiques = jamais redémarré)' if p0 == p1 else '(DIFFÉRENTS : redémarré, voir le journal)')
fatal = [i for i, l in enumerate(lines) if 'FATAL EXCEPTION' in l]
ours = [i for i in fatal if any(PKG in lines[j] for j in range(i, min(i + 3, len(lines))))]
log('  plantages de l\'appli :', len(ours))
for i in ours[:5]:
    log('\n'.join('    ' + lines[j] for j in range(i, min(i + 25, len(lines)))))
anr = [l for l in lines if 'ANR in ' + PKG in l]
log('  blocages (ANR) :', len(anr))
for l in anr[:5]:
    log('    ' + l)
pids = set(filter(None, [p0, p1]))
mine = [l for l in lines if any(re.match(r'^\S+\s+\S+\s+%s\s' % re.escape(p), l) for p in pids)]
errs = [l for l in mine if re.match(r'^\S+\s+\S+\s+\d+\s+\d+\s+E\s', l)]
log('  lignes d\'erreur venant de l\'appli :', len(errs))
seen = set()
for l in errs:
    key = re.sub(r'^\S+\s+\S+\s+\d+\s+\d+\s+', '', l)[:140]
    if key not in seen:
        seen.add(key)
        if len(seen) <= 30:
            log('    ' + key)
skipped = [l for l in mine if 'Choreographer' in l and 'Skipped' in l]
log('  alertes « images sautées » :', len(skipped))
for l in skipped[:10]:
    log('    ' + re.sub(r'^\S+\s+\S+\s+\d+\s+\d+\s+', '', l))
memory('fin du test')
log('\n=== FIN DU TEST ===')
