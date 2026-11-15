#!/usr/bin/env python3
"""
Enregistre à l'avance toutes les phrases du guidage avec la voix française « Siwis » (Piper, hors ligne).
Sortie : un fichier .ogg par phrase, nommé <distance>_<action>.ogg (ou <action>.ogg pour « maintenant »).
Voix : modèle Piper fr_FR-siwis-medium, entraîné sur la base SIWIS (licence CC BY 4.0).
"""
import os, subprocess, sys, wave

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
DIST = {
    'd100': 'Dans cent mètres, ', 'd200': 'Dans deux cents mètres, ', 'd300': 'Dans trois cents mètres, ',
    'd400': 'Dans quatre cents mètres, ', 'd500': 'Dans cinq cents mètres, ', 'd800': 'Dans huit cents mètres, ',
    'd1000': 'Dans un kilomètre, ', 'd2000': 'Dans deux kilomètres, ',
}
ACT = {
    'droite': 'tourne à droite', 'gauche': 'tourne à gauche',
    'leg_droite': 'tourne légèrement à droite', 'leg_gauche': 'tourne légèrement à gauche',
    'fort_droite': 'tourne fortement à droite', 'fort_gauche': 'tourne fortement à gauche',
    'garde_droite': 'garde la droite', 'garde_gauche': 'garde la gauche',
    'droit': 'continue tout droit',
    'rp1': 'au rond-point, prends la première sortie', 'rp2': 'au rond-point, prends la deuxième sortie',
    'rp3': 'au rond-point, prends la troisième sortie', 'rp4': 'au rond-point, prends la quatrième sortie',
    'rp5': 'au rond-point, prends la cinquième sortie', 'rp6': 'au rond-point, prends la sixième sortie',
    'sortie': 'prends la sortie', 'sortie_gauche': 'prends la sortie à gauche',
    'demi_tour': 'fais demi-tour', 'arrivee': 'tu arrives à destination',
}
EXTRA = {'arrive': 'Tu es arrivé.', 'nouvel': 'Nouvel itinéraire.', 'depart': "C'est parti !"}

base = 'https://huggingface.co/rhasspy/piper-voices/resolve/main/fr/fr_FR/siwis/medium/'
os.makedirs('voix', exist_ok=True)
for f in ('fr_FR-siwis-medium.onnx', 'fr_FR-siwis-medium.onnx.json', 'MODEL_CARD'):
    if not os.path.exists('voix/' + f):
        subprocess.run(['curl', '-fsSL', '--retry', '3', '-o', 'voix/' + f, base + f], check=True)
from piper import PiperVoice
v = PiperVoice.load('voix/fr_FR-siwis-medium.onnx')

def say(name, text):
    wav = os.path.join('voix', name + '.wav')
    with wave.open(wav, 'wb') as w:
        try:
            v.synthesize_wav(text, w)
        except AttributeError:
            v.synthesize(text, w)
    # Ogg Vorbis (lu par tous les Android), mono, un peu de silence au début pour ne pas couper la première syllabe
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', wav, '-af', 'adelay=120,apad=pad_dur=0.15,loudnorm=I=-16:TP=-1.5',
                    '-ac', '1', '-ar', '22050', '-c:a', 'libvorbis', '-q:a', '3', os.path.join(OUT, name + '.ogg')], check=True)

n = 0
for a, t in ACT.items():
    say(a, t[0].upper() + t[1:] + '.')
    n += 1
    for d, p in DIST.items():
        say(d + '_' + a, p + t + '.')
        n += 1
for k, t in EXTRA.items():
    say(k, t)
    n += 1
print('phrases :', n)
