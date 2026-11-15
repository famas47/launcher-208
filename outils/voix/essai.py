#!/usr/bin/env python3
"""Fait parler plusieurs voix françaises (hors ligne) sur des phrases de navigation, pour choisir la voix du GPS."""
import json, os, subprocess, sys, wave

PHRASE = ("Dans trois cents mètres, tourne à droite. "
          "Ensuite, au rond-point, prends la deuxième sortie. "
          "Tu es arrivé.")
OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
res = []

def piper_voice(name, quality, label, speaker=None):
    base = 'https://huggingface.co/rhasspy/piper-voices/resolve/main/fr/fr_FR/%s/%s/' % (name, quality)
    model = 'fr_FR-%s-%s.onnx' % (name, quality)
    for f in (model, model + '.json', 'MODEL_CARD'):
        dst = os.path.join('voix', name + '-' + quality + '-' + f) if f == 'MODEL_CARD' else os.path.join('voix', f)
        if not os.path.exists(dst):
            subprocess.run(['curl', '-fsSL', '--retry', '3', '-o', dst, base + f], check=False)
    card = os.path.join('voix', name + '-' + quality + '-MODEL_CARD')
    print('=====', label, '=====')
    if os.path.exists(card):
        print(open(card, encoding='utf-8', errors='replace').read())
    from piper import PiperVoice
    v = PiperVoice.load(os.path.join('voix', model))
    out = os.path.join(OUT, label + '.wav')
    with wave.open(out, 'wb') as w:
        try:
            from piper import SynthesisConfig
            cfg = SynthesisConfig(speaker_id=speaker) if speaker is not None else None
            v.synthesize_wav(PHRASE, w, syn_config=cfg)
        except (ImportError, AttributeError, TypeError):
            v.synthesize(PHRASE, w, speaker_id=speaker)
    res.append(out)

os.makedirs('voix', exist_ok=True)
VOICES = [
    ('siwis', 'medium', 'Voix 1 - femme (Siwis)', None),
    ('upmc', 'medium', 'Voix 2 - femme (Jessica)', 0),
    ('upmc', 'medium', 'Voix 3 - homme (Pierre)', 1),
    ('tom', 'medium', 'Voix 4 - homme (Tom)', None),
    ('gilles', 'low', 'Voix 5 - homme (Gilles)', None),
]
for v in VOICES:
    try:
        piper_voice(*v)
    except Exception as e:
        print('ÉCHEC', v, e)

# Kokoro (voix française ff_siwis), plus naturelle mais plus lourde : seulement pour fabriquer des sons à l'avance
try:
    from kokoro import KPipeline
    import numpy as np, soundfile as sf
    pipe = KPipeline(lang_code='f')
    audio = [a for _, _, a in pipe(PHRASE, voice='ff_siwis')]
    out = os.path.join(OUT, 'Voix 6 - femme (Kokoro Siwis).wav')
    sf.write(out, np.concatenate([np.asarray(a) for a in audio]), 24000)
    res.append(out)
    print('===== Kokoro : modèle Kokoro-82M (Apache 2.0), voix ff_siwis =====')
except Exception as e:
    print('Kokoro : ÉCHEC', e)

# MP3 pour le téléphone
mp3 = []
for w in res:
    m = w[:-4] + '.mp3'
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', w, '-ac', '1', '-b:a', '96k', m], check=True)
    mp3.append(m)
print('fichiers :', mp3)
