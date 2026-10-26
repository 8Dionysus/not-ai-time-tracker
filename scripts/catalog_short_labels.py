#!/usr/bin/env python3
"""Catalog step 0.9.2.0 -> 0.9.3.0.

* Every locale pack gets short activity names that match the short Russian names (localization/catalog-short/c*.txt,
  one row per type in the order of order.json). The previous long label moves into the aliases, so search still finds it.
* A small set of activities that do not belong in a time tracker (intimate, grim or second-long micro-tasks, plain
  duplicates) ships archived, with an explicit `archive` revision. Installed copies retire only where nothing in the
  user's data points at them.
* Two misplaced activities move to the area they belong to.

Meaning (definitions, IDs, selectable, versions, suggestions) is unchanged except for the parents of the moved entries.
Run on the pristine 0.9.2.0 tree: python3 scripts/catalog_short_labels.py
"""
import glob, hashlib, json, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
R = os.path.join(ROOT, 'catalog')
SHORT = os.path.join(ROOT, 'localization', 'catalog-short')
FROM, VERSION = '0.9.2.0', '0.9.3.0'
LANGS = ['en', 'es', 'fr', 'pt-BR', 'de', 'it', 'ar', 'zh-Hans', 'ja', 'ko', 'hi']

ARCHIVE = [
    # Intimate: not something a time tracker should offer as a card.
    'activity.self_care.affectionate_contact', 'activity.self_care.intimate_activity',
    # Grim, misplaced or seconds-long professional micro-steps.
    'isco.research.base.unit_action_proposal.isco08.action.healthcare.veterinary_euthanasia',
    'isco.research.major345.action_candidate.veterinary_reproductive_support',
    'isco.research.major345.action_candidate.manage_alcohol_service_limits',
    'isco.research.major345.action_candidate.verify_customer_age_for_restricted_sale',
    'isco.research.major345.action_candidate.set_gambling_odds_and_bet_risk',
    'isco.research.major345.action_candidate.process_classified_ad_orders',
    'isco.research.major345.action_candidate.operate_library_av_reprographic_equipment',
    'isco.research.major345.action_candidate.administer_periodical_subscriptions',
    'isco.research.major345.action_candidate.assess_sport_event_conditions',
    'isco.research.major345.action_candidate.redeem_pledged_collateral',
    'isco.research.major345.action_candidate.verify_guest_credit_authorization',
    # Gambling variants that repeat «Казино» or take seconds.
    'activity.leisure_sport.games.gambling.onlai_n_gembling', 'activity.leisure_sport.games.gambling.igrovye_avtomaty',
    'activity.leisure_sport.games.gambling.lotereya_rozygrysh_kak_igrovoe_uchastie',
    # Duplicates of a sibling or near sibling.
    'activity.leisure.unstructured_rest', 'activity.errands.market_window_browsing',
    'proposed.craft.quilt_project', 'activity.leisure_sport.creative.c3_tekstil_volokno_i_shite.loskutnoe_shite',
    'proposed.craft.quilting_stitching',
    'activity.leisure_sport.movement.kole_snye_i_skolzyashchie.adaptive_handcycling',
    'activity.leisure_sport.movement.velosipednye_distsipliny.ezda_na_handcycle_ruchnom_velosipede',
    'activity.leisure_sport.movement.para_i_kolyasochnye_vidy.handcycling_para_cycling',
    'activity.proposed_sport.arisf.079', 'activity.proposed_sport.arisf.046',
    'activity.leisure_sport.movement.water.canoe_sprint_slalom',
    'activity.leisure_sport.movement.gibkost_kontrol_tela_mindful_movement.tsigun_gunfu_uprazhneniya',
    'activity.proposed_sport.model_aircraft_piloting',
    'isco.research.base.minor_action_proposal.isco08.minor.342.action.officiate_sport_competition',
    'activity.leisure_sport.movement.kole_snye_i_skolzyashchie.wheelchair_push_workout_sportivnyi_zaezd_na_kresle',
    'proposed.movement.wheelchair_push_run',
    'activity.leisure_sport.movement.le_gkaya_atletika_i_gimnastika.sportivnaya_khudozhestvennaya_gimnastika',
    'activity.household.garden',
    'activity.sleep.night_sleep_detail', 'activity.sleep.nap_detail',
    'activity.self_care.solitude', 'activity.self_care.other',
    'activity.social.personal_correspondence',
    'activity.leisure_sport.media.browse.prosmatrivat_katalog_striminga',
    'activity.leisure_sport.movement.tochnost_metkost_pole_i_individualnaya_tekhnika.konnyi_sport_vyezdka_konkur_konnoe_troebore',
]
MOVES = {
    # «Генеалогия» sat under watching nature outdoors.
    'activity.leisure_sport.outdoor_leisure.b2_nablyudenie_i_poznanie_prirody_na_meste.izuchat_semei_nuyu_istoriyu_zanimatsya_genealogiei':
        'area.education_self_directed',
    # «Градостроительство» sat under organizing one's own work.
    'isco.research.base.unit_action_proposal.isco08.unit.2164.action.plan_spatial_land_use': 'area.legal_and_public_administration',
}
# Stray Cyrillic left in three packs by earlier passes (a typo, a source word quoted in a description, a native script name).
TEXT_FIXES = [
    ('कосп्ले', 'कॉस्प्ले'),
    ('「вышибалы（ヴィシバリー）」は地域によって異なるルールを指す場合がある。', '地域によってルールが異なる場合がある。'),
    (' (қазақ күресі)', ''), ('(қазақ күресі)', ''), ('（Қазақ күресі）', ''), ('қазақ күресі (kazakh kuresi)', 'kazakh kuresi'),
]
NOTE = ('Short activity names rewritten for 0.9.3.0 from the reviewed labels to match the short Russian names; '
        'the previous label is kept as an alias. Native review of the short names is pending.')


def sha(b): return hashlib.sha256(b).hexdigest()
def enc_compact(v): return (json.dumps(v, ensure_ascii=False, separators=(',', ':')) + "\n").encode()
def enc_pack(v): return (json.dumps(v, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode()
def fp(t):
    o = {"kind": "type", "id": t['id'], "definition": t['definition'], "parentId": t.get('parentId'), "selectable": t['selectable'],
         "meaningVersion": t['meaningVersion'], "suggestedAxisIds": sorted(t['suggestedAxisIds']),
         "suggestedMetricIds": sorted(t['suggestedMetricIds'])}
    return sha(json.dumps(o, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode())


def short_labels(types):
    order = json.load(open(os.path.join(SHORT, 'order.json')))
    assert sorted(order) == sorted(types), 'order.json must list every type once'
    rows = {}
    for f in sorted(glob.glob(os.path.join(SHORT, 'c*.txt'))):
        for n, line in enumerate(open(f, encoding='utf-8'), 1):
            if not line.strip(): continue
            cells = [c.strip() for c in line.rstrip('\n').split('‖')]
            assert len(cells) == 1 + len(LANGS) and all(cells), (f, n, 'needs an index and %d labels' % len(LANGS))
            i = int(cells[0]); tid = order[i - 1]
            assert tid not in rows, (f, n, 'duplicate row')
            rows[tid] = dict(zip(LANGS, cells[1:]))
    assert set(rows) == set(types), 'missing rows: %d' % (len(types) - len(rows))
    return rows


def main():
    raw = open(R + '/runtime-catalog.json', 'rb').read(); c = json.loads(raw)
    assert enc_compact(c) == raw, 'catalog encoding differs'
    m = json.load(open(R + '/manifest.json'))
    assert m['catalogVersion'] == FROM and sha(raw) == m['sha256'], 'run on the pristine %s catalog' % FROM
    comp_raw = open(R + '/compatibility.json', 'rb').read(); comp = json.loads(comp_raw)
    assert sha(comp_raw) == m['compatibility']['sha256']
    T = {t['id']: t for t in c['types']}
    labels = short_labels(T)
    for i in ARCHIVE: assert i in T and not T[i].get('archived') and not any(t.get('parentId') == i for t in c['types']), i
    for k, v in MOVES.items(): assert k in T and v in T and T[k]['parentId'] != v, (k, v)

    old = {(r['kind'], r['id']): r for r in comp}
    out, revs = [], []
    for t in c['types']:
        n = dict(t); before = fp(t)
        if t['id'] in ARCHIVE: n['archived'] = True
        if t['id'] in MOVES: n['parentId'] = MOVES[t['id']]
        out.append(n)
        prev = old.pop(('type', t['id']), None)
        if t['id'] not in ARCHIVE and t['id'] not in MOVES:
            if prev: revs.append(prev)
            continue
        r = dict(prev) if prev else {"kind": "type", "id": t['id'], "fromMeaningSha256": before, "toMeaningSha256": before}
        if t['id'] in MOVES:
            # Installs on 0.9.2.0 hold `before`; older ones hold the previous step's source fingerprint.
            also = [x for x in [r['fromMeaningSha256']] + r.get('alsoFromMeaningSha256', []) if x != before]
            r['fromMeaningSha256'] = before; r['toMeaningSha256'] = fp(n); r['parentMove'] = True
            if also: r['alsoFromMeaningSha256'] = sorted(set(also))
        else:
            assert r['toMeaningSha256'] == before
        if t['id'] in ARCHIVE: r['archive'] = True
        revs.append(r)
    revs.extend(old.values())
    assert len({(r['kind'], r['id']) for r in revs}) == len(revs)

    cat = dict(c); cat['types'] = out
    cat_raw = enc_compact(cat); comp_out = enc_compact(revs)
    open(R + '/runtime-catalog.json', 'wb').write(cat_raw)
    open(R + '/compatibility.json', 'wb').write(comp_out)
    m['catalogVersion'] = VERSION; m['bytes'] = len(cat_raw); m['sha256'] = sha(cat_raw)
    m['compatibility']['bytes'] = len(comp_out); m['compatibility']['sha256'] = sha(comp_out)
    open(R + '/manifest.json', 'w').write(json.dumps(m, ensure_ascii=False, indent=2) + "\n")

    for d in sorted(glob.glob(R + '/locales/*/') + glob.glob(R + '/editorial/*/')):
        tag = d.rstrip('/').split('/')[-1]
        mp = json.load(open(d + 'manifest.json')); lraw = open(d + 'labels.json', 'rb').read(); lab = json.loads(lraw)
        assert enc_pack(lab) == lraw, (tag, 'pack encoding differs')
        assert mp['catalogRevision'] == FROM, tag
        seen = set()
        for e in lab['entries']:
            if e['key']['kind'] != 'TYPE': continue
            tid = e['key']['stableId']; seen.add(tid)
            new = labels[tid][tag]; text = e['text']
            aliases, dropped = [], []
            for a in [text['label']] + text['aliases']:
                if a.strip() and a.casefold() != new.casefold() and a.casefold() not in {x.casefold() for x in aliases}:
                    aliases.append(a)
                else: dropped.append(a)
            # A complete pack keeps at least as many aliases as the source entry; refill with the exact old strings.
            need = len(T[tid]['aliases'])
            while len(aliases) < need: aliases.append(dropped.pop(0))
            text['label'] = new; text['aliases'] = aliases
        for e in lab['entries']:
            text = e['text']
            for a, b in TEXT_FIXES:
                text['aliases'] = [x.replace(a, b).strip() for x in text['aliases']]
                text['description'] = text['description'].replace(a, b)
        assert seen == set(T), (tag, 'pack does not cover every type')
        nraw = enc_pack(lab); open(d + 'labels.json', 'wb').write(nraw)
        mp['catalogRevision'] = VERSION; mp['catalogSha256'] = m['sha256']
        mp['payload']['bytes'] = len(nraw); mp['payload']['sha256'] = sha(nraw)
        if NOTE not in mp.get('editor', ''): mp['editor'] = (mp.get('editor') or '') + '; ' + NOTE
        open(d + 'manifest.json', 'w').write(json.dumps(mp, ensure_ascii=False, indent=2) + "\n")
    print('types', len(out), 'archived', len(ARCHIVE), 'moved', len(MOVES), 'revisions', len(revs))
    print('sha', m['sha256'])


if __name__ == '__main__':
    sys.exit(main())
