#!/usr/bin/env python3
"""
Test automatique du launcher sur un émulateur Android 1280x720 (lancé par .github/workflows/verifier.yml).

Il installe l'APK de test, mesure le démarrage, fait tourner la voiture au doigt, ouvre les portières,
passe sur tous les écrans, simule la conduite avec la vraie carte de Belgique, teste la bulle de volume,
cherche les fuites de mémoire, puis fouille le journal d'Android à la recherche du moindre plantage.

Pour juger la fluidité honnêtement : l'émulateur dessine sans carte graphique (tout au processeur),
donc chaque mesure sépare le travail de l'appli (fil principal) du dessin (fil de rendu), et on mesure
aussi une référence : l'appli Paramètres d'Android, sur le même émulateur.

Utilisation : test_emulateur.py <apk> <dossier-sortie> <api> [belgium.map]
"""
import os
import re
import statistics
import subprocess
import sys
import time

APK, OUT, API = sys.argv[1], sys.argv[2], int(sys.argv[3])
MAP = sys.argv[4] if len(sys.argv) > 4 else ''
PKG = 'be.pa.launcher208'
ACT = PKG + '/.HomeActivity'
SETTINGS = 'com.android.settings'
os.makedirs(OUT, exist_ok=True)
RES = open(os.path.join(OUT, 'resume.txt'), 'w', encoding='utf-8')
ROOT = False


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


def last(s):
    s = s.strip().splitlines()
    return s[-1] if s else '(rien)'


def phase(name):
    """Repère dans le journal d'Android, pour savoir pendant quelle étape un souci arrive."""
    adb('shell log -t Test208 "PHASE ' + name.replace('"', '') + '"')


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


def pid():
    return adb('shell pidof ' + PKG).strip()


def swipe(x1, y1, x2, y2, ms):
    adb('shell input swipe %d %d %d %d %d' % (x1, y1, x2, y2, ms))


def gfx_reset(pkg=PKG):
    adb('shell dumpsys gfxinfo %s reset' % pkg)


def pct(v, p):
    v = sorted(v)
    return v[min(len(v) - 1, int(len(v) * p))] if v else 0


def frame_split(pkg):
    """Temps par image : travail de l'appli (fil principal) et dessin (fil de rendu + processeur graphique)."""
    t = adb('shell dumpsys gfxinfo %s framestats' % pkg)
    ui, rt = [], []
    for block in t.split('---PROFILEDATA---')[1::2]:
        rows = [r for r in block.strip().splitlines() if r.strip()]
        if len(rows) < 2:
            continue
        head = rows[0].strip().strip(',').split(',')
        ix = {h: i for i, h in enumerate(head)}
        need = ('Flags', 'HandleInputStart', 'SyncQueued', 'SyncStart', 'FrameCompleted')
        if not all(n in ix for n in need):
            continue
        for r in rows[1:]:
            c = r.strip().strip(',').split(',')
            try:
                if int(c[ix['Flags']]) != 0:
                    continue
                u = (int(c[ix['SyncQueued']]) - int(c[ix['HandleInputStart']])) / 1e6
                d = (int(c[ix['FrameCompleted']]) - int(c[ix['SyncStart']])) / 1e6
                if 0 <= u < 5000 and 0 <= d < 5000:
                    ui.append(u)
                    rt.append(d)
            except (ValueError, IndexError):
                pass
    return ui, rt


def gfx(label, pkg=PKG):
    t = adb('shell dumpsys gfxinfo ' + pkg)
    with open(os.path.join(OUT, 'fluidite_' + re.sub(r'\W+', '_', label) + '.txt'), 'w', encoding='utf-8') as f:
        f.write(t)

    def g(p):
        m = re.search(p, t)
        return m.group(1) if m else '?'
    ui, rt = frame_split(pkg)
    split = ''
    if ui:
        split = ' | appli : médiane %.1f ms (90 %% sous %.1f) ; dessin : médiane %.1f ms (90 %% sous %.1f)' % (
            statistics.median(ui), pct(ui, 0.9), statistics.median(rt), pct(rt, 0.9))
    log('  %s : %s images, lentes %s, médiane %s ms, 90 %% sous %s ms, 99 %% sous %s ms%s' % (
        label, g(r'Total frames rendered: (\d+)'), g(r'Janky frames: (\d+ \([\d.]+%\))'),
        g(r'50th percentile: (\d+)ms'), g(r'90th percentile: (\d+)ms'), g(r'99th percentile: (\d+)ms'), split))
    causes = []
    for key, name in (('Slow UI thread', 'appli trop lente'), ('Slow bitmap uploads', 'envoi d\'images'),
                      ('Slow issue draw commands', 'dessin trop long'), ('Frame deadline missed', 'image en retard'),
                      ('High input latency', 'toucher en retard')):
        m = re.search(r'Number %s: (\d+)' % key, t)
        if m and int(m.group(1)) > 0:
            causes.append('%s %s' % (name, m.group(1)))
    if causes:
        log('      causes signalées par Android : ' + ', '.join(causes))


def gc():
    """Force le ramasse-miettes avant de mesurer la mémoire (sinon on compte aussi les déchets)."""
    p = pid()
    if ROOT and p:
        adb('shell kill -10 ' + p)
        time.sleep(3)


def memory(label):
    gc()
    t = adb('shell dumpsys meminfo ' + PKG)
    with open(os.path.join(OUT, 'memoire_' + re.sub(r'\W+', '_', label) + '.txt'), 'w', encoding='utf-8') as f:
        f.write(t)
    m = re.search(r'TOTAL PSS:\s+(\d+)', t) or re.search(r'\n\s+TOTAL\s+(\d+)', t)
    gr = re.search(r'Graphics:\s+(\d+)', t)
    nat = re.search(r'Native Heap:\s+(\d+)', t)
    java = re.search(r'Java Heap:\s+(\d+)', t)
    views = re.search(r'\n\s+Views:\s+(\d+)', t)
    acts = re.search(r'Activities:\s+(\d+)', t)
    mb = lambda x: ('%.0f Mo' % (int(x.group(1)) / 1024)) if x else '?'
    log('  mémoire %s : total %s (Java %s, natif/images %s, graphique %s) ; vues %s, activités %s' % (
        label, mb(m), mb(java), mb(nat), mb(gr), views.group(1) if views else '?', acts.group(1) if acts else '?'))
    return int(m.group(1)) // 1024 if m else -1


def bubble_shown():
    return 'Bulle volume 208' in adb('shell dumpsys window windows')


def car_bounds():
    hook(info=1)
    time.sleep(1.5)
    t = adb('logcat -d -s Test208:I')
    m = re.findall(r'voiture (\d+) (\d+) (\d+) (\d+)', t)
    if m:
        return tuple(int(v) for v in m[-1])
    log('  (position de la voiture introuvable, valeur par défaut)')
    return 154, 120, 720, 360


log('=== Test du launcher sur émulateur Android (API %d) ===' % API)
log('Android', adb('shell getprop ro.build.version.release').strip())
log('Écran :', adb('shell wm size').strip().replace('\n', ' | '), '|', adb('shell wm density').strip().replace('\n', ' | '))
r = adb('root', 60)
time.sleep(3)
adb('wait-for-device', 120)
ROOT = adb('shell id -u').strip() == '0'
log('Mesure de mémoire après nettoyage :', 'oui' if ROOT else 'non (émulateur sans accès administrateur)')

# ---------- Installation et autorisations ----------
log('\n--- Installation ---')
adb('logcat -G 16M')
adb('logcat -c')
log(' ', last(adb('install -r -g "%s"' % APK, 300)))
for op in ('SYSTEM_ALERT_WINDOW', 'GET_USAGE_STATS'):
    adb('shell appops set %s %s allow' % (PKG, op))
adb('shell cmd notification allow_listener %s/%s.NotifListener' % (PKG, PKG))
log('  écran d\'accueil par défaut :', last(adb('shell cmd package set-home-activity ' + ACT)))
adb('shell settings put system screen_off_timeout 1800000')
# Le message d'Android « Viewing full screen / plein écran » (affiché une seule fois) cachait le haut des captures.
adb('shell settings put secure immersive_mode_confirmations confirmed')
adb('shell svc power stayon true')
adb('emu geo fix 3.9517 50.4542')  # Grand-Place de Mons

if MAP and os.path.exists(MAP):
    d = '/sdcard/Android/data/%s/files' % PKG
    adb('shell mkdir -p ' + d)
    log('  carte de Belgique copiée :', last(adb('push "%s" %s/belgium.map' % (MAP, d), 900)))

# ---------- Démarrage ----------
log('\n--- Démarrage à froid (appli complètement fermée), 3 essais ---')
phase('démarrage')
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
log('  temps total mesuré par Android, du lancement à la première image : %s ms' % ', '.join(map(str, times)))
marks = re.findall(r'temps (.+?) (\d+) ms', adb('logcat -d -s Test208:I'))
if marks:
    log('  détail du dernier démarrage (depuis la création du processus) :')
    for what, ms in marks[-5:]:
        log('    %-45s %5s ms' % (what, ms))
hook(theme=1)  # jour pour les captures
time.sleep(2)
p0 = pid()
log('  numéro du processus :', p0)
shot('01-accueil', 3)
mem_home = memory('accueil (72 vues préchargées)')

phase('repos')
gfx_reset()
time.sleep(6)
gfx('accueil au repos 6 s (doit être presque 0 image)')

# ---------- Référence : l'appli Paramètres d'Android sur le même émulateur ----------
log('\n--- Référence : défilement dans Paramètres d\'Android (même émulateur) ---')
phase('référence')
adb('shell am start -W -a android.settings.SETTINGS')
time.sleep(2)
gfx_reset(SETTINGS)
for k in range(3):
    swipe(640, 600, 640, 150, 500)
    time.sleep(1)
    swipe(640, 150, 640, 600, 500)
    time.sleep(1)
gfx('Paramètres d\'Android (référence)', SETTINGS)
adb('shell am start -n ' + ACT)
time.sleep(2)

# ---------- Voiture 360° ----------
log('\n--- Voiture 360° au doigt ---')
phase('voiture')
x, y, w, h = car_bounds()
cx, cy = x + w // 2, y + h // 2
log('  voiture à l\'écran : x=%d y=%d, %dx%d' % (x, y, w, h))
time.sleep(1)
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
phase('portières')
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
phase('écrans')
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
phase('conduite')
hook(vitesse=50)
time.sleep(3)
gfx_reset()
lat, lon = 50.4542, 3.9517
for k in range(20):                        # environ 14 m par seconde vers l'est
    lon += 0.000197
    adb('emu geo fix %.6f %.6f' % (lon, lat))
    time.sleep(1)
gfx('conduite avec minimap')
dm = re.findall(r'temps (conduite : .+?) (\d+) ms', adb('logcat -d -s Test208:I'))
if len(dm) >= 2:
    log('  passage en mode conduite (fil principal) :')
    t0 = int(dm[-4][1]) if len(dm) >= 4 else int(dm[0][1])
    for what, ms in dm[-4:]:
        log('    %-35s +%d ms' % (what, int(ms) - t0))
shot('13-conduite', 0.5)
log('  (sur la capture 13, la rue affichée doit être la rue d\'Havré, celle que suit la voiture)')
memory('conduite avec la carte')
hook(vitesse=-1)
shot('14-retour-accueil', 2)

# ---------- Nuit ----------
log('\n--- Mode nuit ---')
phase('nuit')
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
# Paramètres d'Android ne convient pas : depuis Android 12, il cache volontairement les bulles des autres applis.
pkgs = adb('shell pm list packages')
installed = {l.strip()[8:] for l in pkgs.splitlines() if l.strip().startswith('package:')}
STANDIN = next((p for p in ('com.android.deskclock', 'com.google.android.deskclock', 'com.android.contacts',
                            'com.android.documentsui', 'com.android.gallery3d') if p in installed), SETTINGS)
log('\n--- Bulle de volume (%s joue le rôle de S-Play) ---' % STANDIN)
phase('bulle')
hook(carplay=STANDIN)
time.sleep(1)
adb('shell monkey -p %s -c android.intent.category.LAUNCHER 1' % STANDIN)
shot('18-bulle-sur-carplay', 4)
log('  bulle affichée par-dessus « CarPlay » :', 'oui' if bubble_shown() else 'NON (ANORMAL)')
m = re.findall(r'(\d+)x(\d+)', adb('shell wm size'))
W, H = (int(m[-1][0]), int(m[-1][1])) if m else (1280, 720)
k = min(W / 1280.0, H / 720.0)
bx, by = int(W - 14 * k - 30 * k), int(H / 2)
adb('shell input tap %d %d' % (bx, by))
shot('19-bulle-ouverte', 1.2)
log('  grand panneau ouvert au toucher :', 'oui' if 'Panneau volume 208' in adb('shell dumpsys window windows') else 'NON (ANORMAL)')
time.sleep(4)                                   # se referme seule après 3 s
shot('20-bulle-refermee', 0.5)
w = adb('shell dumpsys window windows')
log('  panneau refermé seul après 3 s :', 'oui' if 'Panneau volume 208' not in w and 'Bulle volume 208' in w else 'NON (ANORMAL)')
swipe(bx, by, bx - 400, by - 200, 800)          # déplacer la bulle au doigt
shot('21-bulle-deplacee', 1.5)
adb('shell input keyevent 3')                   # bouton accueil
time.sleep(2)
hook(carplay='')
shot('22-retour-accueil-bouton', 1)
log('  bulle cachée quand on revient à l\'accueil :', 'non (ANORMAL)' if bubble_shown() else 'oui')

# ---------- Fuites de mémoire : on répète 5 fois les actions lourdes ----------
log('\n--- Fuites de mémoire : 5 conduites et 5 jour/nuit d\'affilée ---')
phase('fuites')
m0 = memory('avant les répétitions')
for k in range(5):
    hook(vitesse=50); time.sleep(4)
    hook(vitesse=-1); time.sleep(2)
m1 = memory('après 5 entrées/sorties du mode conduite')
for k in range(5):
    hook(theme=2); time.sleep(2)
    hook(theme=1); time.sleep(2)
m2 = memory('après 5 passages jour/nuit')
for k in range(5):
    hook(ecran=k % 5); time.sleep(1)
hook(ecran=0); time.sleep(1)
m3 = memory('après le tour des écrans')
if min(m0, m1, m2, m3) > 0:
    log('  évolution : %+d Mo, puis %+d Mo, puis %+d Mo (stable = pas de fuite)' % (m1 - m0, m2 - m1, m3 - m2))

# ---------- Stress : 1500 touchers au hasard ----------
log('\n--- Stress : 1500 touchers au hasard (monkey) ---')
phase('stress')
r = adb('shell monkey -p %s -s 208 --throttle 60 --pct-syskeys 0 --pct-appswitch 0 --pct-anyevent 0 -v 1500' % PKG, 600)
with open(os.path.join(OUT, 'monkey.txt'), 'w', encoding='utf-8') as f:
    f.write(r)
log('  ', 'terminé sans plantage' if 'Monkey finished' in r else 'INTERROMPU : voir monkey.txt')
time.sleep(2)
adb('shell am start -n ' + ACT)
shot('23-apres-stress', 2)
phase('fin')

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
started = set(re.findall(r'Start proc (\d+):%s' % re.escape(PKG), lc)) | set(filter(None, [p0, p1]))
cur_phase = 'installation'
errs, skipped, seen = [], [], set()
for l in lines:
    if 'Test208' in l and 'PHASE ' in l:
        cur_phase = l.split('PHASE ', 1)[1].strip()
        continue
    m = re.match(r'^\S+\s+\S+\s+(\d+)\s+\d+\s+([VDIWEF])\s+(.*)$', l)
    if not m or m.group(1) not in started:
        continue
    if m.group(2) in 'EF':
        errs.append((cur_phase, m.group(3)))
    if 'Choreographer' in l and 'Skipped' in l:
        skipped.append((cur_phase, m.group(3)))
log('  lignes d\'erreur venant de l\'appli :', len(errs))
for ph, msg in errs:
    key = msg[:150]
    if key not in seen:
        seen.add(key)
        if len(seen) <= 30:
            log('    [%s] %s' % (ph, key))
log('  alertes « images sautées » (fil principal bloqué) :', len(skipped))
for ph, msg in skipped[:10]:
    log('    [%s] %s' % (ph, msg))
memory('fin du test')
log('\n=== FIN DU TEST ===')
