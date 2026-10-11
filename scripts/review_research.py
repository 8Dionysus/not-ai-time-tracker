"""Reproducible per-source audit; preserves definitions and component proposals, never migrates data."""
import json, hashlib
from pathlib import Path
from collections import Counter
ROOT=Path(__file__).resolve().parents[1]
def read(p): return json.loads(p.read_text())
def write(name,obj): (ROOT/'evidence/research'/name).write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n')
x=read(ROOT/'research/catalog.research.json')
support={p.name:read(p) if p.suffix=='.json' else p.read_text() for p in (ROOT/'research/supporting').iterdir()}
manifest={r['file']:r for r in x['manifest']['sourceManifest']}
verified=[]
for name in sorted(support):
 b=(ROOT/'research/supporting'/name).read_bytes(); sha=hashlib.sha256(b).hexdigest();assert sha==manifest[name]['sha256']
 verified.append(dict(file=name,bytes=len(b),sha256=sha,transport='Library read windows; one terminal LF restored; exact manifest digest'))
axes=[]
old={('seed','axis.topic'),('work_learning','axis.topic'),('work_learning','axis.source'),('life_care','axis.topic'),('life_care','axis.source')}
for i,r in enumerate(x['axesReview']['rows']):
 s=r['sourceRecord'];kind=s.get('valueKind');card=s.get('cardinality');dis=r['disposition']; key=(r['sourceInventory'],r['sourceAxisId'])
 blocked=dis in ['move_to_preference','move_to_domain_record'] or key==('work_learning','axis.project')
 compatible=not blocked and ((kind in ['free_label','local_choice'] and card in ['single','multiple','single_optional']) or (kind=='user-selected local value or text' and card=='optional; multiple labels may coexist when true'))
 choices={v['valueId']:v.get('label',v['valueId']) for f in ['curatedSuggestedValues','examples','suggestedValues'] for v in s.get(f,[]) if isinstance(v,dict) and 'valueId' in v} if kind=='local_choice' else {}
 if compatible:
  representation=('LOCAL_CHOICE '+str(card)+'; declared valueId/label pairs only; explicit saveAxis registers personal choices' if kind=='local_choice' else 'FREE_LABEL '+('MULTIPLE' if kind=='user-selected local value or text' or card=='multiple' else 'SINGLE')+'; exact user text, no ID/alias inferred')
  gap=('Proposed normalization remains separate: '+r['reasonRu']+' Source cardinality is unchanged; compound values do not imply known shares. Identity/event/privacy semantics beyond a label are not manufactured.')
  if kind=='local_choice' and not choices: gap+=' Source string examples have no declared stable valueId: initial registry intentionally empty; caller explicitly registers values.'
 elif dis=='move_to_preference':
  representation='Exact research retained; do not encode privacy/display/search settings as analytical facets.';gap='Need separate per-card exposure/search/discreet-label preferences; recommend optional card settings, not activity time fields. No UI authorized.'
 elif dis=='move_to_domain_record':
  representation='Exact field definitions retained. Existing note can carry prose, but is not a typed observation record.';gap='Need observation ID and typed species/count/time/location fields if structured observations are chosen. Recommend separate optional observation records linked to sessions; do not fake them as one facet.'
 elif key==('work_learning','axis.project'):
  representation='Exact projectId requirement retained. Existing FREE_LABEL supports spelling only.';gap='Source free_label declaration conflicts with stable projectId definition and project/client examples. Options: local-choice registry for stable project IDs, or explicit project entity shared by sessions. Recommend local-choice registry for simple references; separate client axis before cross-client reports.'
 else:
  representation='configureAxis explicitly creates a source-local FREE_LABEL or LOCAL_CHOICE, SINGLE or MULTIPLE with caller-chosen local ID. No shape is forced.'
  gap='No schema migration needed. Recommend MULTIPLE free labels for descriptive overlapping facets; registered SINGLE choices only for one deliberately chosen status/question. Exact semantic boundary: '+r['reasonRu']
 axes.append(dict(pointer=f'/axesReview/rows/{i}',sourceInventory=key[0],sourceAxisId=key[1],previouslyRemaining=key not in old,originalDefinition=s,sourceReview=r,coreConstraint='Facet = source-local axis/version plus text or registered value ID/version. No object graph, privacy enforcement or secondary interval.',representation=representation,runtimeId=f'research:{key[0]}:{key[1]}' if compatible else None,declaredChoices=choices,exactRemainingGap=gap))
values=[]
for i,v in enumerate(x['axisValueMapping']['valueRecords']):
 a=next(a for a in axes if (a['sourceInventory'],a['sourceAxisId'])==(v['sourceInventory'],v['sourceAxisId']))
 structure=v['mappingStructure'];components=v.get('componentProposals',[])
 representation='Exact original occurrence remains in archive and ledger. '
 if a['runtimeId']:
  representation+='Runtime may keep the whole source-local text or an explicitly registered source-local value; it never automatically treats components as synonyms or assigns multiple facets.'
 else: representation+='Explicit configureAxis is available only where source schema was absent; other domain-record/preferences remain outside facets.'
 values.append(dict(pointer=f'/axisValueMapping/valueRecords/{i}',original=v,coreConstraint='One facet carries one literal text or one registered value ID. Multiple facets require independent explicit values, not slash splitting.',representation=representation,componentReview=[dict(source=c,allowedRepresentation='Separate optional source-local value under the stated target concept only after explicit schema/value registration; retain original literal occurrence and component relation.',remainingGap=v.get('retainedAmbiguity')) for c in components],remainingGap=v.get('retainedAmbiguity') or ('Research component/alias proposal is not a migration; register chosen local IDs explicitly.' if structure!='single_value_candidate' else 'No cross-source identity inferred.')))
# No generic fallback: each of the 71 source occurrences has an explicit reviewed policy.
policy=read(ROOT/'core/src/main/resources/research-quantity-projections.json')
assert policy['packageSha256']==hashlib.sha256((ROOT/'research/catalog.research.json').read_bytes()).hexdigest()
by_pointer={r['pointer']:r for r in policy['rows']}
assert len(by_pointer)==71
originals={}
for i,r in enumerate(x['quantitiesReview']['rows']):originals[f'/quantitiesReview/rows/{i}']=r
for i,r in enumerate(x['householdQuantities']['candidateMeasures']):originals[f'/householdQuantities/candidateMeasures/{i}']=r
w=support['tracker_work_learning_core_v4_compatibility.json']
for group in ['quantities','reusedExistingDefinitions']:
 for i,r in enumerate(w[group]):originals[f'/supporting/tracker_work_learning_core_v4_compatibility.json/{group}/{i}']=r
assert by_pointer.keys()==originals.keys()
quantities=[]
for pointer,r in originals.items():
 decision=by_pointer[pointer]
 assert decision['sourceId']==r.get('sourceMeasureId',r.get('id'))
 assert decision['definition']==r.get('meaningRu',r.get('definitionRu',r.get('ruleRu')))
 quantities.append(dict(pointer=pointer,original=r,reviewedDefinition=decision['definition'],
  coreConstraint='Whole SESSION/SEGMENT scalar only for NUMBER variants; FACET variants use Axis. No automatic SUM, object/event registry, signed delta or state engine.',
  allowedRepresentations=decision['variants'],exactRemainingGap=decision['remainingGap'],
  api='quantityVocabulary with explicit basis/unit/scope or quantityFacetVocabulary for category/reference; no variants means no projection',
  wordBasisReview=r.get('wordCountVariants',[])))
scalar=[r['sourceId'] for r in policy['rows'] if any(v['kind']=='NUMBER' for v in r['variants'])]

nodes=[]
for r in x['lifeSourceKindReview']['items']:
 node=next(n for n in x['nodes'] if n['id']==r['sourceId'])
 if 'passive_supervision' in node['id']:rep='primarySupervisionVocabulary creates a separately named explicit primary action. No second timeline; concurrent responsibility still has no interval model.'
 elif 'kinship' in node['id']:rep='Optional life_care relationship_private free-label axis already available. Does not create elapsed activity or infer a relationship.'
 else:rep='Instant event log needs event ID/type/timestamp and optional links. Recommend separate event record; time spent documenting may be a separately created personal activity, never duration inferred from the event.'
 nodes.append(dict(original=node,decision=r,allowedRepresentation=rep,coreConstraint='One primary duration stream; facets are classifications, not event records.'))
write('followup-axis-review.json',axes);write('followup-value-review.json',values);write('followup-quantity-review.json',quantities);write('followup-node-review.json',nodes)
summary=dict(verifiedDocuments=verified,axesReviewed=len(axes),previouslyRemainingAxes=sum(a['previouslyRemaining'] for a in axes),runtimeAxes=sum(a['runtimeId'] is not None for a in axes),explicitSchemaAxes=sum('configureAxis' in a['representation'] for a in axes),valuesReviewed=len(values),valueStructures=dict(Counter(v['original']['mappingStructure'] for v in values)),quantityRecordsReviewed=len(quantities),wordBasesReviewed=6,nonRuntimeNodesReviewed=len(nodes),numericSourceRecords=len(scalar),numericVariants=sum(v["kind"]=="NUMBER" for r in policy["rows"] for v in r["variants"]),facetVariants=sum(v["kind"]=="FACET" for r in policy["rows"] for v in r["variants"]),quantityPolicy="core/src/main/resources/research-quantity-projections.json",mobileScope=support['tracker_mobile_catalog_navigation_research.json']['scope'],mobileRemainder=support['tracker_mobile_catalog_navigation_research.json']['concrete_remainder'])
write('followup-summary.json',summary)
print(json.dumps({k:v for k,v in summary.items() if k not in ['verifiedDocuments','mobileRemainder']},ensure_ascii=False))
