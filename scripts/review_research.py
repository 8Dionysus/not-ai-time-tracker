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
quantities=[]
for i,r in enumerate(x['quantitiesReview']['rows']):
 duration=r['proposedConcepts']==['quantity.elapsed_duration']
 bounded=r['sourceMeasureId'] in ['measure.distance','measure.elevation_gain','measure.sets','measure.repetitions','measure.pages']
 quantities.append(dict(pointer=f'/quantitiesReview/rows/{i}',original=r,coreConstraint='Nonnegative scalar SESSION/SEGMENT target; no event/object/position lineage.',allowedRepresentation='Core segment duration, never another metric.' if duration else 'Implemented measureVocabulary: explicitly named bounded manual total, BY_TARGET, source provenance; not unique coverage or position.' if bounded else 'A separately named nonnegative manual scalar with explicit unit/basis/scope is possible; BY_TARGET avoids unjustified roll-up. Original multi-part quantity remains in source.',exactRemainingGap=None if duration else dict(requiredContext=r.get('requiredInterpretationContext'),meaning=r.get('meaningRu'),aggregation=r.get('aggregationRu'),disposition=r['disposition'])))
# Each household candidate keeps its own definition, aggregation and the exact missing target context.
for i,r in enumerate(x['householdQuantities']['candidateMeasures']):
 k=r['measureKind']
 if k=='state_plus_interval': rep='No scalar projection for recurrence/due-state; optional task records with recurrence convention needed.'
 elif k in ['quantity_state','quantity_context']: rep='One scalar snapshot/package size is storable BY_TARGET only as a session annotation, not stock state or package conversion. Need object ID, snapshot time/basis.'
 elif k=='money_amount':rep='A nonnegative amount in one chosen currency fits BY_TARGET; no exchange-rate conversion. Receipt/line identity, refunds and dedup need separate transaction semantics.'
 elif 'event' in k or k=='count_or_extent':rep='Explicitly known aggregate fits integer SESSION/SEGMENT BY_TARGET, with separate unit/kind/stage. Full meaning needs unique event/object/batch IDs; never derive count from timer segments.'
 else:rep='A named count/mass/volume/area/length component fits scalar BY_TARGET. Compound units are separate metrics; source-specific product/site/stage/basis remains explicit, not inferred.'
 quantities.append(dict(pointer=f'/householdQuantities/candidateMeasures/{i}',original=r,coreConstraint='Only SESSION/SEGMENT numeric targets, not dated object/event/task state.',allowedRepresentation=rep,exactRemainingGap=dict(scope=r.get('measurementScope'),context=r.get('optionalContextFields'),aggregation=r['aggregationPolicyRu'],misleadingIf=r.get('misleadingIfRu'))))
w=support['tracker_work_learning_core_v4_compatibility.json']
scalar={'qneed.work.length_quantity','qneed.work.area_quantity','qneed.work.volume_quantity','qneed.work.mass_quantity','qneed.learning.response_attempt_count'}
for group in ['quantities','reusedExistingDefinitions']:
 for i,r in enumerate(w[group]):
  quantities.append(dict(pointer=f'supporting/tracker_work_learning_core_v4_compatibility.json/{group}/{i}',original=r,coreConstraint=w['actualCoreContract']['contractLimitations'],allowedRepresentation='Implemented scalarVocabulary: explicit local meaning/scope, SI unit or response count, BY_TARGET, verified provenance.' if r['id'] in scalar else r['coreMapping'],exactRemainingGap=r.get('exactGapOrBoundary',r.get('ruleRu')),wordBasisReview=r.get('wordCountVariants',[])))
nodes=[]
for r in x['lifeSourceKindReview']['items']:
 node=next(n for n in x['nodes'] if n['id']==r['sourceId'])
 if 'passive_supervision' in node['id']:rep='primarySupervisionVocabulary creates a separately named explicit primary action. No second timeline; concurrent responsibility still has no interval model.'
 elif 'kinship' in node['id']:rep='Optional life_care relationship_private free-label axis already available. Does not create elapsed activity or infer a relationship.'
 else:rep='Instant event log needs event ID/type/timestamp and optional links. Recommend separate event record; time spent documenting may be a separately created personal activity, never duration inferred from the event.'
 nodes.append(dict(original=node,decision=r,allowedRepresentation=rep,coreConstraint='One primary duration stream; facets are classifications, not event records.'))
write('followup-axis-review.json',axes);write('followup-value-review.json',values);write('followup-quantity-review.json',quantities);write('followup-node-review.json',nodes)
summary=dict(verifiedDocuments=verified,axesReviewed=len(axes),previouslyRemainingAxes=sum(a['previouslyRemaining'] for a in axes),runtimeAxes=sum(a['runtimeId'] is not None for a in axes),explicitSchemaAxes=sum('configureAxis' in a['representation'] for a in axes),valuesReviewed=len(values),valueStructures=dict(Counter(v['original']['mappingStructure'] for v in values)),quantityRecordsReviewed=len(quantities),wordBasesReviewed=6,nonRuntimeNodesReviewed=len(nodes),boundedScalarFactories=sorted(scalar),mobileScope=support['tracker_mobile_catalog_navigation_research.json']['scope'],mobileRemainder=support['tracker_mobile_catalog_navigation_research.json']['concrete_remainder'])
write('followup-summary.json',summary)
print(json.dumps({k:v for k,v in summary.items() if k not in ['verifiedDocuments','mobileRemainder']},ensure_ascii=False))
