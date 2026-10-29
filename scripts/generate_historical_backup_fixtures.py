#!/usr/bin/env python3
"""Synthetic, schema-based fixtures for the historical whole-object exporters.

The version/commit mapping is documented in samples/backup-schema-history.md.
This script does not read or contain user backups. Non-default settings exercise
preservation; optional values not represented here need separate coverage.
"""
import copy
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
base = json.loads((ROOT / 'samples/history-v2.json').read_text(encoding='utf-8'))
fixtures = []
for version in range(2, 17):
    archive = copy.deepcopy(base)
    archive['formatVersion'] = version
    data = archive['data']
    for item in data['types']:
        item.update(parentId=None, selectable=True, meaningVersion=1, aliases=[], definition='',
                    suggestedAxisIds=[], suggestedMetricIds=[])
        if version >= 3:
            item['researchOrigins'] = []
    for item in data['cards']:
        item['facets'] = []
        if version >= 14:
            item['personal'] = {'aliases': ['учёба'], 'order': 5, 'notePolicy': 'CLEAR'}
    for collection in ('favorites', 'sessions', 'segments'):
        for item in data[collection]:
            item['selection']['facets'] = []
    if version >= 4:
        for item in data['sessions']:
            item['importOrigin'] = None
    if version >= 7:
        for item in data['segments']:
            item['limitMs'] = None
    if version >= 12:
        for item in data['segments']:
            item['manualConfirmation'] = {'confirmedAt': archive['asOf']}
    for item in data['measurements']:
        item['source'] = None
    settings = data['settings']
    if version >= 5:
        settings['surfaces'] = {'widget': True, 'notification': False, 'overlay': False}
        if version >= 7:
            settings['surfaces']['focusSignal'] = False
        if version >= 11:
            settings['surfaces']['shortcut'] = False
        if version >= 15:
            settings['surfaces'].update(notificationPrivacy='FULL', widgetBackgroundAlphaPercent=37,
                                        overlayChooserBackgroundAlphaPercent=72)
    if version >= 7:
        settings['focusPlan'] = {'workMs': 1500000, 'breakMs': 300000}
        data.update(focus=None, catalogVersion=None, timerEpoch=0, controlBarrier='')
    if version >= 6:
        data['goals'] = []
    if version >= 8:
        data['schedule'] = {'series': [], 'overrides': [], 'links': [], 'receipts': []}
        data['transfer'] = {'origins': [], 'operations': []}
        if version >= 9:
            data['transfer']['compactions'] = []
    if version >= 10:
        settings['schedule'] = {'day': {'zonePolicy': 'FIXED', 'zoneId': 'Europe/Berlin',
                                        'boundary': '04:00'}, 'quietShadeEnabled': True}
    if version >= 13:
        settings['longRecordReminder'] = {'enabled': True, 'afterMs': 7200000, 'snoozeMs': 900000}
    if version >= 15:
        settings['reportDayStartMinute'] = 275
    fixtures.append(archive)

output = ROOT / 'samples/backup-schema-versions.json'
output.write_text(json.dumps(fixtures, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
