#!/usr/bin/env python3
"""
Fabrique les deux fichiers de la navigation du Launcher 208 à partir d'un fichier OpenStreetMap (.osm.pbf) :

  <sortie>-routes.bin    le réseau routier pour les voitures (sens uniques, vitesses, interdictions de tourner,
                         bornes et barrières), pour calculer les trajets sur l'autoradio ;
  <sortie>-adresses.bin  les localités, rues, adresses et lieux (magasins, hôpitaux...), pour chercher
                         une destination.

Utilisation : construire.py belgium.osm.pbf sortie/belgique
Données © contributeurs OpenStreetMap, licence ODbL.

Format : petit-boutiste (little-endian), coordonnées en millionièmes de degré (entiers).
"""
import collections
import math
import re
import struct
import sys
import time
import unicodedata

import numpy as np
import osmium

T0 = time.time()


def log(*a):
    print('[%5.0f s]' % (time.time() - T0), *a, flush=True)


# ---------------------------------------------------------------------------------------------------------
# Routes pour voitures : rang (0 = autoroute ... 7 = petite route) et vitesse habituelle (km/h)
ROADS = {
    'motorway': (0, 120), 'motorway_link': (0, 60),
    'trunk': (1, 90), 'trunk_link': (1, 50),
    'primary': (2, 70), 'primary_link': (2, 40),
    'secondary': (3, 60), 'secondary_link': (3, 40),
    'tertiary': (4, 50), 'tertiary_link': (4, 30),
    'unclassified': (5, 50), 'residential': (6, 30), 'road': (6, 30),
    'living_street': (7, 15), 'service': (7, 15),
}
NO_CAR = {'no', 'private', 'agricultural', 'forestry', 'delivery', 'permit', 'emergency', 'bus', 'psv',
          'official', 'military', 'use_sidepath', 'discouraged'}
PASSABLE_BARRIERS = {'cattle_grid', 'border_control', 'checkpoint', 'toll_booth', 'sally_port', 'gate',
                     'lift_gate', 'no', 'entrance', 'height_restrictor', 'arch', 'swing_gate'}


def car_allowed(tags):
    if tags.get('area') == 'yes':
        return False
    if tags.get('highway') == 'service' and tags.get('service') == 'emergency_access':
        return False
    for k in ('motorcar', 'motor_vehicle', 'vehicle', 'access'):
        v = tags.get(k)
        if v is not None:
            return v.split(';')[0].strip() not in NO_CAR
    return True


def oneway(tags):
    o = None
    for k in ('oneway:motorcar', 'oneway:motor_vehicle', 'oneway:vehicle', 'oneway'):
        if k in tags:
            o = tags[k]
            break
    if o in ('yes', 'true', '1'):
        return 1
    if o in ('-1', 'reverse'):
        return -1
    if o == 'no':
        return 0
    if tags.get('junction') in ('roundabout', 'circular') or tags.get('highway') == 'motorway':
        return 1
    return 0


def parse_speed(v):
    if not v:
        return None
    v = v.strip()
    m = re.match(r'^(\d+)', v)
    if m:
        s = int(m.group(1))
        if 'mph' in v:
            s = int(s * 1.609)
        return s if 5 <= s <= 150 else None
    u = v.lower()
    if 'zone30' in u or 'zone:30' in u:
        return 30
    if 'living_street' in u:
        return 20
    if 'motorway' in u:
        return 120
    if 'urban' in u:
        return 30 if 'bru' in u else 50
    if 'rural' in u:
        return 90 if 'wal' in u else 70
    if u == 'walk':
        return 6
    return None


def speed_kmh(tags, hw):
    s = parse_speed(tags.get('maxspeed:forward')) or parse_speed(tags.get('maxspeed')) or ROADS[hw][1]
    if hw == 'service' and tags.get('service') in ('parking_aisle', 'driveway', 'drive-through'):
        s = min(s, 10)
    s = min(s, 120)
    # vitesse moyenne réelle (carrefours, feux, virages) plus basse que la limite
    f = 0.92 if s >= 90 else 0.8 if s >= 50 else 0.7
    return max(4, int(round(s * f)))


# ---------------------------------------------------------------------------------------------------------
# Recherche : texte simplifié (minuscules, sans accents) et catégories de lieux
def norm(s):
    s = s.lower().replace('œ', 'oe').replace('æ', 'ae').replace('ß', 'ss').replace('ĳ', 'ij')
    s = unicodedata.normalize('NFKD', s)
    s = ''.join(c for c in s if not unicodedata.combining(c))
    s = re.sub(r"[^a-z0-9]+", ' ', s)
    return s.strip()


def tokens(*texts):
    out = []
    for t in texts:
        if t:
            out.extend(norm(t).split())
    return out


PLACES = {'city': ('Ville', 6), 'town': ('Ville', 5), 'village': ('Village', 4), 'suburb': ('Quartier', 3),
          'quarter': ('Quartier', 2), 'neighbourhood': ('Quartier', 1), 'hamlet': ('Hameau', 1),
          'isolated_dwelling': ('Lieu-dit', 0), 'locality': ('Lieu-dit', 0)}
LOCALITY_TYPES = {'city', 'town', 'village', 'suburb'}

POI = {
    ('amenity', 'fuel'): 'Station-service', ('amenity', 'charging_station'): 'Borne de recharge',
    ('amenity', 'parking'): 'Parking', ('amenity', 'hospital'): 'Hôpital', ('amenity', 'clinic'): 'Clinique',
    ('amenity', 'doctors'): 'Médecin', ('amenity', 'dentist'): 'Dentiste', ('amenity', 'pharmacy'): 'Pharmacie',
    ('amenity', 'restaurant'): 'Restaurant', ('amenity', 'fast_food'): 'Restauration rapide',
    ('amenity', 'cafe'): 'Café', ('amenity', 'bar'): 'Bar', ('amenity', 'pub'): 'Bar',
    ('amenity', 'school'): 'École', ('amenity', 'university'): 'Université', ('amenity', 'college'): 'Haute école',
    ('amenity', 'kindergarten'): 'École maternelle', ('amenity', 'townhall'): 'Maison communale',
    ('amenity', 'police'): 'Police', ('amenity', 'fire_station'): 'Pompiers', ('amenity', 'post_office'): 'Poste',
    ('amenity', 'bank'): 'Banque', ('amenity', 'cinema'): 'Cinéma', ('amenity', 'theatre'): 'Théâtre',
    ('amenity', 'library'): 'Bibliothèque', ('amenity', 'marketplace'): 'Marché',
    ('amenity', 'car_wash'): 'Lavage auto', ('amenity', 'veterinary'): 'Vétérinaire',
    ('amenity', 'nursing_home'): 'Maison de repos', ('amenity', 'social_facility'): 'Service social',
    ('amenity', 'place_of_worship'): 'Lieu de culte', ('amenity', 'community_centre'): 'Centre culturel',
    ('amenity', 'arts_centre'): 'Centre culturel', ('amenity', 'events_venue'): 'Salle',
    ('amenity', 'nightclub'): 'Discothèque',
    ('tourism', 'hotel'): 'Hôtel', ('tourism', 'museum'): 'Musée', ('tourism', 'attraction'): 'Attraction',
    ('tourism', 'camp_site'): 'Camping', ('tourism', 'zoo'): 'Zoo', ('tourism', 'theme_park'): 'Parc d\'attractions',
    ('tourism', 'guest_house'): 'Chambre d\'hôtes',
    ('leisure', 'sports_centre'): 'Centre sportif', ('leisure', 'stadium'): 'Stade',
    ('leisure', 'swimming_pool'): 'Piscine', ('leisure', 'fitness_centre'): 'Salle de sport',
    ('leisure', 'park'): 'Parc', ('leisure', 'water_park'): 'Parc aquatique', ('leisure', 'ice_rink'): 'Patinoire',
    ('railway', 'station'): 'Gare', ('aeroway', 'aerodrome'): 'Aéroport',
    ('shop', 'supermarket'): 'Supermarché', ('shop', 'convenience'): 'Épicerie', ('shop', 'bakery'): 'Boulangerie',
    ('shop', 'car_repair'): 'Garage', ('shop', 'car'): 'Garage', ('shop', 'tyres'): 'Pneus',
    ('shop', 'hairdresser'): 'Coiffeur', ('shop', 'mall'): 'Centre commercial', ('shop', 'doityourself'): 'Bricolage',
    ('shop', 'hardware'): 'Bricolage', ('shop', 'furniture'): 'Meubles', ('shop', 'clothes'): 'Vêtements',
    ('shop', 'shoes'): 'Chaussures', ('shop', 'electronics'): 'Électronique', ('shop', 'butcher'): 'Boucherie',
    ('shop', 'optician'): 'Opticien', ('shop', 'florist'): 'Fleuriste', ('shop', 'books'): 'Librairie',
    ('shop', 'department_store'): 'Grand magasin', ('shop', 'garden_centre'): 'Jardinerie',
}
POI_KEYS = ('amenity', 'shop', 'tourism', 'leisure', 'railway', 'aeroway')


def poi_label(tags):
    for k in POI_KEYS:
        v = tags.get(k)
        if v is None:
            continue
        lab = POI.get((k, v))
        if lab:
            return lab
        if k == 'shop' and v not in ('vacant', 'no'):
            return 'Magasin'
    return None


# ---------------------------------------------------------------------------------------------------------
E6 = 1e6


def hav(lat1, lon1, lat2, lon2):
    p = math.pi / 180
    a = math.sin((lat2 - lat1) * p / 2) ** 2 + math.cos(lat1 * p) * math.cos(lat2 * p) * math.sin((lon2 - lon1) * p / 2) ** 2
    return 12742000 * math.asin(math.sqrt(a))


class Grid:
    """Recherche du plus proche dans des cases de 0,05°."""

    def __init__(self, step=0.05):
        self.step = step
        self.cells = collections.defaultdict(list)

    def add(self, lat, lon, item):
        self.cells[(int(lat / self.step), int(lon / self.step))].append((lat, lon, item))

    def nearest(self, lat, lon, maxd, ring=2):
        ci, cj = int(lat / self.step), int(lon / self.step)
        best, bd = None, maxd
        for di in range(-ring, ring + 1):
            for dj in range(-ring, ring + 1):
                for (a, b, it) in self.cells.get((ci + di, cj + dj), ()):
                    d = hav(lat, lon, a, b)
                    if d < bd:
                        bd, best = d, it
        return best


def main():
    src, out = sys.argv[1], sys.argv[2]

    # ----- 1. Lecture -----
    way_ids = array_q()
    way_nodes_start = [0]
    node_ids = array_q()
    node_lat = array_i()
    node_lon = array_i()
    way_info = []  # (rank, speed, oneway, name)
    blocked = set()
    restr = []  # (from_way, via_node, to_way, kind)

    places = []          # (lat, lon, name, type)
    streets = []         # (normnamekey, name, lat, lon)
    addrs = []           # (street, number, lat, lon, city, postcode)
    pois = []            # (name, label, lat, lon)

    keys = ('highway', 'addr:housenumber', 'place', 'barrier', 'restriction', 'restriction:motorcar') + POI_KEYS
    fp = osmium.FileProcessor(src).with_locations().with_filter(osmium.filter.KeyFilter(*keys))
    n_obj = 0
    for o in fp:
        n_obj += 1
        t = o.tags
        if o.is_node():
            if not o.location.valid():
                continue
            lat, lon = o.location.lat, o.location.lon
            b = t.get('barrier')
            if b is not None:
                acc = None
                for k in ('motorcar', 'motor_vehicle', 'vehicle', 'access'):
                    if k in t:
                        acc = t[k]
                        break
                if acc is not None:
                    if acc in NO_CAR:
                        blocked.add(o.id)
                elif b not in PASSABLE_BARRIERS:
                    blocked.add(o.id)
            pl = t.get('place')
            if pl in PLACES and t.get('name'):
                places.append((lat, lon, t.get('name:fr') or t['name'], pl))
            if 'addr:housenumber' in t and ('addr:street' in t or 'addr:place' in t):
                addrs.append((t.get('addr:street') or t.get('addr:place'), t['addr:housenumber'], lat, lon,
                              t.get('addr:city', ''), t.get('addr:postcode', '')))
            if t.get('name'):
                lab = poi_label(t)
                if lab:
                    pois.append((t['name'], lab, lat, lon))
            continue
        if o.is_way():
            nodes = o.nodes
            if len(nodes) < 2:
                continue
            hw = t.get('highway')
            if hw in ROADS and car_allowed(t):
                ok = True
                for n in nodes:
                    if not n.location.valid():
                        ok = False
                        break
                if ok:
                    way_ids.append(o.id)
                    for n in nodes:
                        node_ids.append(n.ref)
                        node_lat.append(int(round(n.lat * E6)))
                        node_lon.append(int(round(n.lon * E6)))
                    way_nodes_start.append(len(node_ids))
                    way_info.append((ROADS[hw][0], speed_kmh(t, hw), oneway(t), t.get('name') or t.get('ref') or ''))
            # rue (aussi piétonne) pour la recherche
            if hw is not None and t.get('name') and hw not in ('footway', 'path', 'cycleway', 'steps', 'bridleway',
                                                                'track', 'proposed', 'construction', 'platform', 'corridor'):
                m = nodes[len(nodes) // 2]
                if m.location.valid():
                    streets.append((t['name'], m.lat, m.lon))
            # adresse ou lieu dessiné comme un bâtiment : son centre (moyenne des points)
            need_addr = 'addr:housenumber' in t and ('addr:street' in t or 'addr:place' in t)
            lab = poi_label(t) if t.get('name') else None
            pl = t.get('place')
            if need_addr or lab or (pl in PLACES and t.get('name')):
                sl = so = 0.0
                c = 0
                for n in nodes:
                    if n.location.valid():
                        sl += n.lat
                        so += n.lon
                        c += 1
                if c:
                    lat, lon = sl / c, so / c
                    if need_addr:
                        addrs.append((t.get('addr:street') or t.get('addr:place'), t['addr:housenumber'], lat, lon,
                                      t.get('addr:city', ''), t.get('addr:postcode', '')))
                    if lab:
                        pois.append((t['name'], lab, lat, lon))
                    if pl in PLACES and t.get('name'):
                        places.append((lat, lon, t.get('name:fr') or t['name'], pl))
            continue
        # relation : interdiction de tourner
        typ = t.get('type')
        if typ not in ('restriction', 'restriction:motorcar', 'restriction:motor_vehicle'):
            continue
        kind = t.get('restriction:motorcar') or t.get('restriction:motor_vehicle') or t.get('restriction')
        if not kind or 'motorcar' in t.get('except', ''):
            continue
        fr, via, to = [], [], []
        for m in o.members:
            if m.role == 'from' and m.type == 'w':
                fr.append(m.ref)
            elif m.role == 'to' and m.type == 'w':
                to.append(m.ref)
            elif m.role == 'via':
                via.append((m.type, m.ref))
        if len(fr) == 1 and len(to) == 1 and len(via) == 1 and via[0][0] == 'n':
            restr.append((fr[0], via[0][1], to[0], kind))
    log('lu : %d objets, %d routes, %d points de route, %d bornes, %d interdictions, %d localités, %d rues, %d adresses, %d lieux'
        % (n_obj, len(way_ids), len(node_ids), len(blocked), len(restr), len(places), len(streets), len(addrs), len(pois)))

    build_routes(out + '-routes.bin', way_ids, way_nodes_start, node_ids, node_lat, node_lon, way_info, blocked, restr)
    build_search(out + '-adresses.bin', places, streets, addrs, pois)
    log('fini')


def array_q():
    import array
    return array.array('q')


def array_i():
    import array
    return array.array('i')


# ---------------------------------------------------------------------------------------------------------
def build_routes(path, way_ids, way_nodes_start, node_ids, node_lat, node_lon, way_info, blocked, restr):
    ids = np.frombuffer(node_ids, dtype=np.int64)
    lat = np.frombuffer(node_lat, dtype=np.int32)
    lon = np.frombuffer(node_lon, dtype=np.int32)
    starts = np.array(way_nodes_start, dtype=np.int64)
    # Carrefours : points utilisés plus d'une fois, bouts de route, bornes
    uniq, counts = np.unique(ids, return_counts=True)
    junction = set(uniq[counts >= 2].tolist())
    junction.update(ids[starts[:-1]].tolist())
    junction.update(ids[starts[1:] - 1].tolist())
    junction.update(blocked)
    log('carrefours : %d' % len(junction))

    node_index = {}
    j_lat, j_lon, j_flag = [], [], []

    def nid(osm_id, la, lo):
        k = node_index.get(osm_id)
        if k is None:
            k = len(j_lat)
            node_index[osm_id] = k
            j_lat.append(la)
            j_lon.append(lo)
            j_flag.append(1 if osm_id in blocked else 0)
        return k

    e_u, e_v, e_len, e_speed, e_flags, e_name, e_way = [], [], [], [], [], [], []
    geo_start = [0]
    g_lat, g_lon = [], []
    names = {'': 0}
    name_list = ['']
    ids_l = ids.tolist()
    lat_l = lat.tolist()
    lon_l = lon.tolist()
    for w in range(len(way_ids)):
        a, b = int(starts[w]), int(starts[w + 1])
        rank, spd, ow, name = way_info[w]
        nm = names.get(name)
        if nm is None:
            nm = len(name_list)
            names[name] = nm
            name_list.append(name)
        flags = (1 if ow >= 0 else 0) | (2 if ow <= 0 else 0) | (rank << 2)
        seg_start = a
        length = 0.0
        for i in range(a + 1, b):
            length += hav(lat_l[i - 1] / E6, lon_l[i - 1] / E6, lat_l[i] / E6, lon_l[i] / E6)
            if ids_l[i] in junction or i == b - 1:
                u = nid(ids_l[seg_start], lat_l[seg_start], lon_l[seg_start])
                v = nid(ids_l[i], lat_l[i], lon_l[i])
                if u != v or i - seg_start > 1:
                    e_u.append(u)
                    e_v.append(v)
                    e_len.append(max(1, int(round(length * 10))))
                    e_speed.append(spd)
                    e_flags.append(flags)
                    e_name.append(nm)
                    e_way.append(way_ids[w])
                    for k in range(seg_start + 1, i):
                        g_lat.append(lat_l[k])
                        g_lon.append(lon_l[k])
                    geo_start.append(len(g_lat))
                seg_start = i
                length = 0.0
    n_nodes, n_edges = len(j_lat), len(e_u)
    log('réseau : %d carrefours, %d tronçons, %d points de forme' % (n_nodes, n_edges, len(g_lat)))

    # Liste des tronçons au départ de chaque carrefour (dans le sens autorisé) : 2e = u→v, 2e+1 = v→u
    eu = np.array(e_u, dtype=np.int32)
    ev = np.array(e_v, dtype=np.int32)
    ef = np.array(e_flags, dtype=np.uint8)
    src_nodes, dirs = [], []
    fwd = (ef & 1) != 0
    bwd = (ef & 2) != 0
    eidx = np.arange(n_edges, dtype=np.int32)
    out_from = np.concatenate([eu[fwd], ev[bwd]])
    out_id = np.concatenate([eidx[fwd] * 2, eidx[bwd] * 2 + 1])
    order = np.argsort(out_from, kind='stable')
    out_from = out_from[order]
    out_id = out_id[order].astype(np.int32)
    first_out = np.searchsorted(out_from, np.arange(n_nodes + 1)).astype(np.int32)
    # Réseau principal : carrefours d'où l'on peut aller partout et revenir (en respectant les sens uniques
    # et les bornes). Les petits morceaux isolés (parking fermé, cour derrière une barrière...) ne servent
    # pas de point de départ ou d'arrivée : le trajet commencerait ou finirait dans une impasse.
    from scipy.sparse import csr_matrix
    from scipy.sparse.csgraph import connected_components
    blk = np.array(j_flag, dtype=np.uint8)
    head = np.where((out_id & 1) == 0, ev[out_id >> 1], eu[out_id >> 1])
    keep = blk[out_from] == 0
    adj = csr_matrix((np.ones(int(keep.sum()), dtype=np.int8), (out_from[keep], head[keep])), shape=(n_nodes, n_nodes))
    ncomp, comp = connected_components(adj, directed=True, connection='strong')
    big = np.bincount(comp).argmax()
    main_edge = (comp[eu] == big) & (comp[ev] == big)
    ef = ef | np.where(main_edge, 0, 32).astype(np.uint8)
    log('réseau principal : %d carrefours sur %d, %d tronçons isolés' % (int((comp == big).sum()), n_nodes, int((~main_edge).sum())))
    # Nombre de tronçons touchant chaque carrefour (impasse = 1)
    deg = np.bincount(np.concatenate([eu, ev]), minlength=n_nodes)
    deg = np.minimum(deg, 255).astype(np.uint8)

    # Interdictions de tourner (tronçon d'arrivée, carrefour, tronçon de départ)
    want = set()
    for (fw, via, tw, kind) in restr:
        want.add(fw)
        want.add(tw)
    touch = collections.defaultdict(list)  # (way, node index) -> tronçons
    for e in range(n_edges):
        if e_way[e] in want:
            touch[(e_way[e], e_u[e])].append(e)
            touch[(e_way[e], e_v[e])].append(e)
    incident = collections.defaultdict(set)
    bans = set()
    n_ok = 0
    for (fw, via, tw, kind) in restr:
        vn = node_index.get(via)
        if vn is None:
            continue
        fe = touch.get((fw, vn), [])
        te = touch.get((tw, vn), [])
        if not fe or not te:
            continue
        n_ok += 1
        if kind.startswith('no_'):
            for a in fe:
                for b in te:
                    bans.add((vn, a, b))
        elif kind.startswith('only_'):
            if vn not in incident:
                lo_, hi_ = first_out[vn], first_out[vn + 1]
                incident[vn] = set(int(x) >> 1 for x in out_id[lo_:hi_])
            for a in fe:
                for b in incident[vn]:
                    if b not in te:
                        bans.add((vn, a, b))
    bans = sorted(bans)
    log('interdictions de tourner : %d utilisées, %d paires interdites' % (n_ok, len(bans)))

    # Grille pour trouver la route la plus proche d'un point (cases de 0,005°)
    step = 5000  # en millionièmes de degré
    all_lat = np.array(j_lat, dtype=np.int64)
    all_lon = np.array(j_lon, dtype=np.int64)
    lat0 = int(all_lat.min()) - step
    lon0 = int(all_lon.min()) - step
    gh = int((all_lat.max() - lat0) // step) + 2
    gw = int((all_lon.max() - lon0) // step) + 2
    pairs = set()
    for e in range(n_edges):
        pts = [(j_lat[e_u[e]], j_lon[e_u[e]])]
        for k in range(geo_start[e], geo_start[e + 1]):
            pts.append((g_lat[k], g_lon[k]))
        pts.append((j_lat[e_v[e]], j_lon[e_v[e]]))
        for k in range(len(pts)):
            la, lo = pts[k]
            pairs.add((((la - lat0) // step) * gw + (lo - lon0) // step, e))
            if k + 1 < len(pts):
                la2, lo2 = pts[k + 1]
                n = int(max(abs(la2 - la), abs(lo2 - lo)) // (step // 2))
                for s in range(1, n + 1):
                    a = la + (la2 - la) * s // (n + 1)
                    b = lo + (lo2 - lo) * s // (n + 1)
                    pairs.add((((a - lat0) // step) * gw + (b - lon0) // step, e))
    pairs = np.array(sorted(pairs), dtype=np.int64).reshape(-1, 2)
    cell_start = np.searchsorted(pairs[:, 0], np.arange(gw * gh + 1)).astype(np.int32)
    cell_edges = pairs[:, 1].astype(np.int32)
    log('grille : %d x %d cases, %d entrées' % (gw, gh, len(cell_edges)))

    name_bytes = bytearray()
    name_start = []
    for s in name_list:
        name_start.append(len(name_bytes))
        name_bytes += s.encode('utf-8')
    name_start.append(len(name_bytes))

    sections = [
        np.array(j_lat, dtype=np.int32), np.array(j_lon, dtype=np.int32),
        np.array(j_flag, dtype=np.uint8), deg,
        first_out, out_id,
        eu, ev, np.array(e_len, dtype=np.int32), np.array(e_speed, dtype=np.uint8), ef,
        np.array(e_name, dtype=np.int32), np.array(geo_start, dtype=np.int32),
        np.array(g_lat, dtype=np.int32), np.array(g_lon, dtype=np.int32),
        np.array([b[0] for b in bans], dtype=np.int32), np.array([b[1] for b in bans], dtype=np.int32),
        np.array([b[2] for b in bans], dtype=np.int32),
        cell_start, cell_edges,
        np.array(name_start, dtype=np.int32), np.frombuffer(bytes(name_bytes), dtype=np.uint8),
    ]
    counts = [n_nodes, n_edges, len(g_lat), len(name_list), len(bans), gw, gh, len(cell_edges), len(out_id),
              lat0, lon0, step]
    write_file(path, b'R208ROUT', counts, sections)


# ---------------------------------------------------------------------------------------------------------
def build_search(path, places, streets, addrs, pois):
    loc_grid = Grid()     # villes et villages (la commune ou l'ancienne commune)
    sub_grid = Grid()     # quartiers
    for (la, lo, name, pl) in places:
        if pl in ('city', 'town', 'village'):
            loc_grid.add(la, lo, name)
        elif pl in ('suburb', 'quarter', 'neighbourhood'):
            sub_grid.add(la, lo, name)

    def locality(la, lo):
        return loc_grid.nearest(la, lo, 15000) or ''

    def quarter(la, lo):
        return sub_grid.nearest(la, lo, 1500, ring=1) or ''

    # Rues : même nom et même localité = une seule rue
    groups = collections.defaultdict(list)
    for (name, la, lo) in streets:
        groups[(norm(name), locality(la, lo))].append((la, lo, name))
    street_entries = []   # [name, loc, lat, lon, houses(list), cities Counter, postcodes Counter]
    by_name = collections.defaultdict(list)
    for (key, loc), pts in groups.items():
        cl = sum(p[0] for p in pts) / len(pts)
        co = sum(p[1] for p in pts) / len(pts)
        best = min(pts, key=lambda p: (p[0] - cl) ** 2 + ((p[1] - co) * 0.64) ** 2)
        names = collections.Counter(p[2] for p in pts)
        ent = [names.most_common(1)[0][0], loc, best[0], best[1], [], collections.Counter(), collections.Counter()]
        street_entries.append(ent)
        by_name[key].append(ent)
    log('rues : %d' % len(street_entries))

    # Adresses : rattachées à la rue du même nom la plus proche (moins de 3 km)
    orphan = 0
    for (st, num, la, lo, city, pc) in addrs:
        key = norm(st)
        best, bd = None, 3000
        for ent in by_name.get(key, ()):
            d = hav(la, lo, ent[2], ent[3])
            if d < bd:
                bd, best = d, ent
        if best is None:
            best = [st, locality(la, lo), la, lo, [], collections.Counter(), collections.Counter()]
            street_entries.append(best)
            by_name[key].append(best)
            orphan += 1
        best[4].append((num.strip(), la, lo))
        if city:
            best[5][city] += 1
        if pc:
            best[6][pc] += 1
    log('adresses : %d (rues ajoutées depuis les adresses : %d)' % (len(addrs), orphan))

    entries = []  # (type, name, sub, lat, lon, importance, houses, token list)
    seen_places = set()
    for (la, lo, name, pl) in places:
        k = (norm(name), pl, round(la, 2), round(lo, 2))
        if k in seen_places:
            continue
        seen_places.add(k)
        lab, imp = PLACES[pl]
        sub = lab
        toks = tokens(name)
        if pl not in ('city', 'town'):
            near = locality(la, lo)
            if near and norm(near) != norm(name):
                sub = lab + ' · ' + near
                toks += tokens(near)
        entries.append((0, name, sub, la, lo, imp, None, toks))
    for ent in street_entries:
        name, loc, la, lo, houses, cities, pcs = ent
        city = cities.most_common(1)[0][0] if cities else loc
        pc = pcs.most_common(1)[0][0] if pcs else ''
        sub = (pc + ' ' + city).strip()
        toks = tokens(name, city, pc)
        if loc and norm(loc) != norm(city):
            toks += tokens(loc)
        entries.append((1, name, sub, la, lo, 2, houses, toks))
    seen_poi = set()
    for (name, lab, la, lo) in pois:
        k = (norm(name), lab, round(la, 3), round(lo, 3))
        if k in seen_poi:
            continue
        seen_poi.add(k)
        loc = locality(la, lo)
        q = quarter(la, lo)
        imp = 2 if lab in ('Hôpital', 'Gare', 'Université', 'Haute école', 'Centre commercial', 'Aéroport') else 1
        entries.append((2, name, lab + (' · ' + loc if loc else ''), la, lo, imp, None, tokens(name, lab, loc, q)))
    log('entrées de recherche : %d' % len(entries))

    # Textes
    blob = bytearray()
    text_off = {}

    def txt(s):
        o = text_off.get(s)
        if o is None:
            o = len(blob)
            b = s.encode('utf-8')[:65535]
            blob.extend(struct.pack('<H', len(b)))
            blob.extend(b)
            text_off[s] = o
        return o

    n = len(entries)
    typ = np.zeros(n, np.uint8)
    imp = np.zeros(n, np.uint8)
    elat = np.zeros(n, np.int32)
    elon = np.zeros(n, np.int32)
    ename = np.zeros(n, np.int32)
    esub = np.zeros(n, np.int32)
    hstart = np.zeros(n + 1, np.int32)
    h_num, h_dx, h_dy = [], [], []
    postings = collections.defaultdict(set)
    for i, (t, name, sub, la, lo, im, houses, toks) in enumerate(entries):
        typ[i] = t
        imp[i] = im
        elat[i] = int(round(la * E6))
        elon[i] = int(round(lo * E6))
        ename[i] = txt(name)
        esub[i] = txt(sub)
        hstart[i] = len(h_num)
        if houses:
            kx = 111320.0 * math.cos(math.radians(la))
            seen = set()
            for (num, hla, hlo) in houses:
                key = num.lower()
                if key in seen or len(num) > 12:
                    continue
                seen.add(key)
                dx = int(round((hlo - lo) * kx))
                dy = int(round((hla - la) * 110540.0))
                if abs(dx) > 32000 or abs(dy) > 32000:
                    continue
                h_num.append(txt(num))
                h_dx.append(dx)
                h_dy.append(dy)
        for tk in set(toks):
            postings[tk].add(i)
    hstart[n] = len(h_num)
    toks_sorted = sorted(postings.keys())
    tok_blob = bytearray()
    tok_start = []
    post_start = [0]
    post = []
    for tk in toks_sorted:
        tok_start.append(len(tok_blob))
        tok_blob += tk.encode('ascii')
        post.extend(sorted(postings[tk]))
        post_start.append(len(post))
    tok_start.append(len(tok_blob))
    log('mots : %d, renvois : %d, numéros : %d, textes : %d Ko' % (len(toks_sorted), len(post), len(h_num), len(blob) // 1024))
    sections = [typ, imp, elat, elon, ename, esub, hstart,
                np.array(h_num, np.int32), np.array(h_dx, np.int16), np.array(h_dy, np.int16),
                np.array(tok_start, np.int32), np.frombuffer(bytes(tok_blob), np.uint8),
                np.array(post_start, np.int32), np.array(post, np.int32),
                np.frombuffer(bytes(blob), np.uint8)]
    counts = [n, len(toks_sorted), len(post), len(h_num), len(blob)]
    write_file(path, b'R208ADRS', counts, sections)


def write_file(path, magic, counts, sections):
    """En-tête : magie (8), version, nombre de valeurs, valeurs, nombre de sections, (début, longueur) de chaque section."""
    head = bytearray(magic)
    head += struct.pack('<ii', 1, len(counts))
    head += struct.pack('<%di' % len(counts), *counts)
    head += struct.pack('<i', len(sections))
    hsize = len(head) + 8 * len(sections)
    hsize = (hsize + 7) // 8 * 8
    offs = []
    pos = hsize
    for s in sections:
        b = np.ascontiguousarray(s).astype(s.dtype.newbyteorder('<'), copy=False).tobytes()
        offs.append((pos, len(b)))
        pos += (len(b) + 7) // 8 * 8
    for (o, l) in offs:
        head += struct.pack('<ii', o, l)
    head += b'\0' * (hsize - len(head))
    with open(path, 'wb') as f:
        f.write(head)
        for s, (o, l) in zip(sections, offs):
            b = np.ascontiguousarray(s).astype(s.dtype.newbyteorder('<'), copy=False).tobytes()
            f.write(b)
            f.write(b'\0' * ((l + 7) // 8 * 8 - l))
    log('%s : %.1f Mo' % (path, pos / 1e6))


if __name__ == '__main__':
    main()
