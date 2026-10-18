"""Strict selected replacement telemetry reader. No compositor/physical/quality verdict."""
import argparse
import json
import math
from pathlib import Path
from collections import Counter

ENVELOPE = {'sequence', 'elapsedNanos', 'observedUptimeMillis', 'wallMillis', 'thread', 'tag', 'message'}
TRACE = {'schemaVersion', 'clockDomain', 'connection', 'stage', 'epoch', 'imageRequest', 'lane', 'atMillis', 'queueMillis',
         'pixelAgeMillis', 'preprocessMillis', 'runtimeMillis', 'postprocessMillis'}
POST = {'schemaVersion', 'clockDomain', 'connection', 'endpointGeneration', 'postSerial', 'recordedAtMillis', 'postReturnedAtMillis',
        'uiAdmittedAtMillis', 'compositorAtMillis', 'queuedNanos', 'lockNanos', 'recordingNanos', 'postNanos',
        'mainThread', 'acceptedPaint', 'memberCount', 'canvasKnown', 'canvasKind', 'epoch', 'sequence',
        'geometry', 'imageRequest', 'pixelAtMillis'}
SCROLL = {'schemaVersion', 'clockDomain', 'connection', 'epoch', 'geometry', 'windowId', 'protectionOrigin', 'tapScope',
          'delivery', 'owner', 'producer', 'sourceAtMillis', 'receivedAtMillis', 'readStartedAtMillis', 'readFinishedAtMillis',
          'admittedAtMillis', 'admission', 'refusal', 'generation', 'producerCount', 'intervalCount', 'x', 'y',
          'reportedViewport', 'pixelPhaseKnown', 'memberApplicabilityKnown', 'physicalErrorKnown'}
SCROLL_V2 = SCROLL | {'ownerKind'}
SCROLL_OWNER_KINDS = ('UNKNOWN', 'RECYCLER_VIEW', 'OTHER')
AXIS = {'valid', 'absoluteKnown', 'absolute', 'deltaKnown', 'delta'}
NATIVE = {'schemaVersion', 'clockDomain', 'connection', 'epoch', 'sequence', 'request', 'queuedAtMillis', 'startedAtMillis',
          'readStartedAtMillis', 'readFinishedAtMillis', 'receivedAtMillis', 'maximumAgeMillis', 'coverage', 'scanGeneration',
          'confirmationGeneration', 'hardGeneration', 'classificationCompletedAtMillis', 'confirmationStartedAtMillis',
          'visitedNodes', 'classifiedNodes', 'confirmationReads', 'candidateCount', 'regionCount', 'nodes'}
NATIVE_NODE = {'validatedAtMillis', 'validUntilMillis', 'textSource', 'matchStart', 'matchEnd', 'normalizedLength', 'lineCount', 'exactGeometry'}
NATIVE_V2 = NATIVE | {'readMode'}
NATIVE_V3 = NATIVE_V2 | {'observationOrdinal'}
READ_MODES = ('FULL_DISCOVERY','ADMITTED_NODE_RECLASSIFY')
COHORT_MODE = 'ADMITTED_NODE_COHORT_RECLASSIFY'
COST_START = {'schemaVersion', 'clockDomain', 'connection', 'request', 'epoch', 'geometry', 'hardGeneration', 'invokedAtMillis'}
COST = COST_START | {'scanGeneration', 'readStartedAtMillis', 'readFinishedAtMillis', 'terminalAtMillis', 'terminal',
                     'durationClock', 'elapsedNanos', 'unattributedNanos', 'rawCostCount', 'costs'}
COST_START_V3 = COST_START | {'readMode'}
COST_V3 = COST | {'readMode'}
COST_STAGES = ('PROVIDER_OPEN', 'PROVIDER_CURRENT', 'NODE_REFRESH', 'CHILD_FETCH', 'CLASSIFY', 'GLYPH_QUERY', 'GEOMETRY_EXCLUSIVE', 'DISPOSE')
COST_STAGES_V2 = COST_STAGES[:2] + ('TRAVERSAL_CURRENT',) + COST_STAGES[2:]


def native_mode(row):
    allowed = (COHORT_MODE,) if row['schemaVersion'] == 4 else READ_MODES
    if row['readMode'] not in allowed: raise ValueError('Unknown/version-mismatched native acquisition mode')


def native_cost_start(row, observed_at):
    if type(row) is not dict: raise ValueError('Unknown reader invocation type')
    expected=COST_START_V3 if row.get('schemaVersion') in (3,4) else COST_START
    if type(row) is not dict or set(row) != expected: raise ValueError('Unknown/incomplete reader invocation')
    for key in COST_START - {'clockDomain'}:
        integer(row[key])
        if row[key] > 9223372036854775807: raise ValueError('Reader invocation integer overflow')
    if row['schemaVersion'] not in (1,2,3,4) or row['clockDomain']!='UPTIME_MILLIS': raise ValueError('Reader invocation schema/clock differs')
    if row['schemaVersion'] in (3,4): native_mode(row)
    for key in ('connection', 'request'): integer(row[key], 1)
    if row['invokedAtMillis'] > observed_at: raise ValueError('Reader invocation is later than its observation')
    return row


def native_cost_row(row, observed_at):
    if type(row) is not dict: raise ValueError('Unknown reader terminal type')
    expected=COST_V3 if row.get('schemaVersion') in (3,4) else COST
    if type(row) is not dict or set(row) != expected: raise ValueError('Unknown/incomplete reader terminal costs')
    start_fields=COST_START_V3 if row['schemaVersion'] in (3,4) else COST_START
    native_cost_start({k: row[k] for k in start_fields}, observed_at)
    for key in ('scanGeneration','readStartedAtMillis','readFinishedAtMillis'):
        integer(row[key], -1)
        if row[key] > 9223372036854775807: raise ValueError('Reader cost clock overflow')
    for key in ('terminalAtMillis','elapsedNanos','unattributedNanos','rawCostCount'):
        integer(row[key])
        if row[key] > 9223372036854775807: raise ValueError('Reader cost duration overflow')
    if row['terminal'] not in ('SUCCESS','FAILURE','CANCELLED','RESOURCE_FAILURE') or row['durationClock'] != 'SYSTEM_NANO_TIME':
        raise ValueError('Unknown reader terminal/duration clock')
    if not row['invokedAtMillis'] <= row['terminalAtMillis'] <= observed_at: raise ValueError('Reader terminal chronology differs')
    if row['readStartedAtMillis'] == -1:
        if row['readFinishedAtMillis'] != -1 or row['scanGeneration'] != -1 or row['terminal']=='SUCCESS':
            raise ValueError('Refused beginRead cannot create successful read clocks')
    elif not row['invokedAtMillis'] <= row['readStartedAtMillis'] <= row['readFinishedAtMillis'] <= row['terminalAtMillis'] or row['scanGeneration'] < 0:
        raise ValueError('Actual read/cleanup clocks reversed')
    costs = row['costs']
    stages=COST_STAGES_V2 if row['schemaVersion'] in (2,3,4) else COST_STAGES
    if type(costs) is not list or len(costs) != len(stages) or row['rawCostCount'] != len(costs):
        raise ValueError('Raw reader cost inventory differs')
    total = 0
    for stage, cost in zip(stages,costs):
        if type(cost) is not dict or set(cost) != {'stage','calls','nanos','maximumNanos','failedCalls'} or cost['stage'] != stage:
            raise ValueError('Unknown/reordered reader cost bucket')
        for key in ('calls','nanos','maximumNanos','failedCalls'):
            integer(cost[key])
            if cost[key] > 9223372036854775807: raise ValueError('Reader cost integer overflow')
        count, nanos, maximum, failed = (cost[k] for k in ('calls','nanos','maximumNanos','failedCalls'))
        if (count > 65536 or failed > count or maximum > nanos or (not count and (nanos or maximum))
                or (count and maximum < (nanos+count-1)//count)):
            raise ValueError('Reader cost count/max/failure attribution differs')
        total += nanos
    if total + row['unattributedNanos'] != row['elapsedNanos'] or row['elapsedNanos'] > 9223372036854775807:
        raise ValueError('Nested/overlapping durations or hidden reader time')
    return row


def reconcile_native_costs(starts, costs, natives):
    def key(row): return (row['connection'],row['epoch'],row['request'])
    beginning={key(row):row for row in starts}; terminal={key(row):row for row in costs}
    if len(beginning)!=len(starts) or len(terminal)!=len(costs): raise ValueError('Duplicate reader invocation/terminal')
    if beginning.keys()!=terminal.keys(): raise ValueError('Reader invocation lacks exactly one actual terminal')
    for identity, start in beginning.items():
        if any(start[k]!=terminal[identity].get(k) for k in start): raise ValueError('Reader terminal borrowed another invocation')
    if not starts and any(row.get('observationOrdinal', 0) > 0 for row in natives):
        raise ValueError('Native observations lack actual parent invocation/terminal coverage')
    if starts or costs:
        for native in natives:
            cost=terminal.get(key(native))
            if cost is None or any(cost[k]!=native[k] for k in ('hardGeneration','scanGeneration','readStartedAtMillis')):
                raise ValueError('Admitted native facts borrowed another actual Reader parent')
            if native.get('observationOrdinal', 0) > 0:
                # The producer is still running here; its genuine later terminal can fail or cancel.
                # This is historical positive admission, not complete/current evidence authority.
                if not native['receivedAtMillis'] <= cost['readFinishedAtMillis'] <= cost['terminalAtMillis']:
                    raise ValueError('Native observation crossed its actual parent lifetime')
            elif (cost['terminal']!='SUCCESS' or cost['readFinishedAtMillis']!=native['readFinishedAtMillis']
                    or cost['terminalAtMillis'] > native['receivedAtMillis']):
                raise ValueError('Admitted native Source lacks its unique successful actual Reader terminal')
            if cost['schemaVersion'] in (3,4) and (native['schemaVersion'] not in (2,3,4) or native['readMode']!=cost['readMode']):
                raise ValueError('Admitted native mode differs from its actual selected operation')


def native_publication(prior_rows, row):
    for prior in prior_rows:
        if prior['connection'] != row['connection'] or prior['epoch'] != row['epoch']:
            continue
        if prior['sequence'] == row['sequence']:
            raise ValueError('Duplicate native Source publication')
        if row['request'] <= 0 or prior['request'] != row['request']:
            continue
        if prior['schemaVersion'] not in (3,4) or row['schemaVersion'] not in (3,4):
            raise ValueError('Legacy native rows cannot establish same-parent observations')
        if prior['observationOrdinal'] == 0:
            raise ValueError('Native parent already terminally admitted')
        if row['observationOrdinal'] and row['observationOrdinal'] <= prior['observationOrdinal']:
            raise ValueError('Repeated or reversed native group ordinal')
        if (any(prior[k] != row[k] for k in ('queuedAtMillis','startedAtMillis','readStartedAtMillis','readMode',
                'hardGeneration','scanGeneration','classificationCompletedAtMillis','confirmationStartedAtMillis',
                'maximumAgeMillis','visitedNodes','classifiedNodes'))
                or row['sequence'] <= prior['sequence'] or row['readFinishedAtMillis'] < prior['readFinishedAtMillis']
                or row['receivedAtMillis'] < prior['receivedAtMillis']
                or row['confirmationGeneration'] < prior['confirmationGeneration']
                or row['confirmationReads'] < prior['confirmationReads']):
            raise ValueError('Native observation/terminal borrowed or regressed parent provenance')
ADMISSIONS = set('BASELINE RECORDED DUPLICATE UNKNOWN GAP STALE CLOSED'.split())
REFUSALS = set('NONE SCOPE CLOCK OWNER CAPACITY ORDER CONFLICT OVERFLOW INVALID_COORDINATE EXPLICIT_GAP SOURCE HISTORY UNKNOWN_AXIS CLOSED'.split())
STAGES = set('CAPTURE_REQUESTED CAPTURE_DISPATCHED CAPTURE_RETURNED CAPTURE_CALLBACK CAPTURE_REJECTED CAPTURE_BACKPRESSURE '
             'SOURCE_REJECTED RESOURCE_FAILED JOB_QUEUED JOB_REPLACED JOB_STARTED JOB_COMPLETED JOB_FAILED JOB_CANCELLED '
             'JOB_REJECTED RESULT_REJECTED PRESENTATION_REPLACED PRESENTATION_REJECTED PRESENTED DRAWN SCOPE_CHANGED'.split())


def strict_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate telemetry field')
        result[key] = value
    return result


def decode(raw):
    return json.loads(raw, object_pairs_hook=strict_object)


def integer(value, minimum=0):
    if type(value) is not int or value < minimum or value >= 2**63:
        raise ValueError('Unsupported telemetry integer')


def scroll_row(row, observed_at):
    expected = SCROLL_V2 if row['schemaVersion'] == 2 else SCROLL
    if set(row) != expected or type(row['admission']) is not str or type(row['refusal']) is not str or row['admission'] not in ADMISSIONS or row['refusal'] not in REFUSALS:
        raise ValueError('Unknown/incomplete scroll receipt')
    if row['schemaVersion'] == 2 and (type(row['ownerKind']) is not str or row['ownerKind'] not in SCROLL_OWNER_KINDS):
        raise ValueError('Unknown scroll owner kind')
    for key in ('epoch', 'delivery', 'owner', 'producer', 'admittedAtMillis', 'generation', 'producerCount', 'intervalCount'):
        integer(row[key])
    integer(row['geometry'], -2**63)
    integer(row['windowId'], -1)
    if row['windowId'] >= 2**31:
        raise ValueError('Unsupported scroll window identity')
    integer(row['tapScope'], -1)
    if row['protectionOrigin'] not in ('UNSPECIFIED', 'NORMAL', 'LAB') or (row['protectionOrigin'] == 'UNSPECIFIED') != (row['tapScope'] == -1):
        raise ValueError('Unknown scroll protection provenance')
    for key in ('sourceAtMillis', 'receivedAtMillis', 'readStartedAtMillis', 'readFinishedAtMillis'):
        # A rejected original clock is still original; never repair it with receipt or zero.
        integer(row[key], -2**63)
    if row['admittedAtMillis'] > observed_at:
        raise ValueError('Scroll admission is later than same-domain observation')
    for key in ('pixelPhaseKnown', 'memberApplicabilityKnown', 'physicalErrorKnown'):
        if row[key] is not False:
            raise ValueError('Ingestion cannot qualify pixels or member motion')
    for axis in ('x', 'y'):
        value = row[axis]
        if type(value) is not dict or set(value) != AXIS or any(type(value[key]) is not bool for key in ('valid', 'absoluteKnown', 'deltaKnown')):
            raise ValueError('Unknown/incomplete scroll coordinate')
        for flag, scalar, minimum in (('absoluteKnown', 'absolute', 0), ('deltaKnown', 'delta', -2**63)):
            if value[flag]:
                integer(value[scalar], minimum)
            elif value[scalar] is not None:
                raise ValueError('Unknown scroll scalar was fabricated')
    viewport = row['reportedViewport']
    if viewport is not None:
        if type(viewport) is not dict or set(viewport) != {'left', 'top', 'right', 'bottom'} or any(
                type(value) not in (int, float) or not math.isfinite(value) for value in viewport.values()):
            raise ValueError('Unknown/nonfinite advisory viewport')
        if viewport['left'] >= viewport['right'] or viewport['top'] >= viewport['bottom']:
            raise ValueError('Empty advisory viewport')
    admitted = row['admission'] in ('BASELINE', 'RECORDED', 'DUPLICATE', 'UNKNOWN')
    clocks_valid = 0 <= row['sourceAtMillis'] <= row['receivedAtMillis'] <= row['readStartedAtMillis'] <= row['readFinishedAtMillis'] <= row['admittedAtMillis']
    if admitted and (not clocks_valid or min(row['owner'], row['producer'], row['delivery']) <= 0 or not row['x']['valid'] or not row['y']['valid']):
        raise ValueError('Admitted scroll has invalid input')
    expected_refusal = dict(BASELINE='NONE', RECORDED='NONE', DUPLICATE='NONE', UNKNOWN='UNKNOWN_AXIS', STALE='SCOPE', CLOSED='CLOSED')
    if row['admission'] in expected_refusal and row['refusal'] != expected_refusal[row['admission']]:
        raise ValueError('Scroll admission/refusal differs')
    if row['admission'] == 'GAP' and (row['refusal'] not in REFUSALS - {'NONE', 'SCOPE', 'SOURCE', 'HISTORY', 'UNKNOWN_AXIS', 'CLOSED'}
                                    or row['producerCount'] != 0 or row['intervalCount'] != 0):
        raise ValueError('Invalid explicit scroll gap')
    if row['refusal'] == 'CLOCK' and clocks_valid:
        raise ValueError('Clock refusal has no original clock failure')
    return row


def native_row(row, observed_at):
    if type(row) is not dict: raise ValueError('Unknown native read type')
    expected=NATIVE_V3 if row.get('schemaVersion') in (3,4) else NATIVE_V2 if row.get('schemaVersion')==2 else NATIVE
    if type(row) is not dict or set(row) != expected or type(row['schemaVersion']) is not int or row['schemaVersion'] not in (1,2,3,4) or row['coverage'] not in ('COMPLETE_TREE', 'POSITIVE_NODES_ONLY'):
        raise ValueError('Unknown/incomplete native read coverage')
    if row['schemaVersion'] in (2,3,4):
        native_mode(row)
        if row['readMode']=='ADMITTED_NODE_RECLASSIFY' and (row['coverage']!='POSITIVE_NODES_ONLY' or
                any(row[key]!=1 or type(row[key]) is not int for key in ('visitedNodes','classifiedNodes','confirmationReads','candidateCount'))):
            raise ValueError('Direct native operation fabricated tree or selected-node coverage')
    if row['schemaVersion'] in (3,4):
        integer(row['observationOrdinal'])
        if row['observationOrdinal'] > 64 or (row['observationOrdinal'] > 0 and
                (row['request'] <= 0 or row['readMode'] not in ('FULL_DISCOVERY',COHORT_MODE) or row['coverage'] != 'POSITIVE_NODES_ONLY'
                 or row['candidateCount'] != 1 or row['confirmationReads'] != row['observationOrdinal'])):
            raise ValueError('Native group ordinal invented terminal/tree/direct authority')
    if row['schemaVersion'] == 4:
        expected_count = 1 if row['observationOrdinal'] == 1 else 2
        if (row['observationOrdinal'] not in (0,1) or row['coverage'] != 'POSITIVE_NODES_ONLY'
                or any(type(row[key]) is not int or row[key] != 2 for key in ('visitedNodes','classifiedNodes'))
                or any(type(row[key]) is not int or row[key] != expected_count for key in ('confirmationReads','candidateCount'))):
            raise ValueError('Cohort invented selected inventory or completed by omission')
    for key in NATIVE - {'schemaVersion', 'clockDomain', 'coverage', 'nodes'}:
        integer(row[key], -1 if key in {'request', 'queuedAtMillis', 'startedAtMillis', 'scanGeneration', 'confirmationGeneration',
                                      'hardGeneration', 'classificationCompletedAtMillis', 'confirmationStartedAtMillis'} else 0)
    if row['sequence'] == 0 or row['maximumAgeMillis'] == 0:
        raise ValueError('Native source/age is missing')
    if not row['readStartedAtMillis'] <= row['readFinishedAtMillis'] <= row['receivedAtMillis'] <= observed_at:
        raise ValueError('Native read/publication clock order differs')
    provenance = [row[k] for k in ('request', 'queuedAtMillis', 'startedAtMillis')]
    if provenance != [-1, -1, -1] and not (row['request'] > 0 and 0 <= row['queuedAtMillis'] <= row['startedAtMillis'] <= row['readStartedAtMillis']):
        raise ValueError('Native job clock order differs')
    revisions = [row[k] for k in ('scanGeneration', 'confirmationGeneration', 'hardGeneration')]
    legacy = revisions == [-1, -1, -1]
    if legacy:
        if row['coverage'] != 'COMPLETE_TREE' or row['classificationCompletedAtMillis'] != -1 or row['confirmationStartedAtMillis'] != -1:
            raise ValueError('Unknown revisions cannot establish partial facts')
    elif not (0 <= row['scanGeneration'] <= row['confirmationGeneration'] and row['hardGeneration'] >= 0
              and row['readStartedAtMillis'] <= row['classificationCompletedAtMillis'] <= row['readFinishedAtMillis']):
        raise ValueError('Native classified revision/clock provenance differs')
    if not legacy and row['coverage'] == 'COMPLETE_TREE' and row['scanGeneration'] != row['confirmationGeneration']:
        raise ValueError('Cross-revision facts relabelled complete')
    count = row['candidateCount']
    if not 0 <= count <= 64 or not count <= row['classifiedNodes'] <= row['visitedNodes'] <= 900 or not count <= row['confirmationReads'] <= 64:
        raise ValueError('Native confirmed inventory differs')
    if not isinstance(row['nodes'], list) or len(row['nodes']) != count:
        raise ValueError('Native candidate raw/parsed count differs')
    if row['coverage'] == 'POSITIVE_NODES_ONLY' and count == 0:
        raise ValueError('Empty partial input cannot be admitted evidence')
    if not legacy:
        if count == 0:
            if row['confirmationStartedAtMillis'] != -1: raise ValueError('Empty read invented confirmation')
        elif not row['classificationCompletedAtMillis'] + 48 <= row['confirmationStartedAtMillis'] <= row['readFinishedAtMillis']:
            raise ValueError('Native confirmation interval is incomplete')
    regions = 0
    for node in row['nodes']:
        if type(node) is not dict or set(node) != NATIVE_NODE or type(node['exactGeometry']) is not bool:
            raise ValueError('Unknown/incomplete native candidate')
        for key in NATIVE_NODE - {'textSource', 'exactGeometry'}: integer(node[key], -1 if key in {'validatedAtMillis', 'matchStart', 'matchEnd', 'normalizedLength'} else 0)
        if node['textSource'] not in ('UNKNOWN', 'TEXT', 'CONTENT_DESCRIPTION') or not 1 <= node['lineCount'] <= 12:
            raise ValueError('Native text source/line count differs')
        span = [node[k] for k in ('matchStart', 'matchEnd', 'normalizedLength')]
        known_span = node['textSource'] != 'UNKNOWN' and 0 <= node['matchStart'] < node['matchEnd'] <= node['normalizedLength'] <= 2000
        if span != [-1, -1, -1] and not 0 <= node['matchStart'] < node['matchEnd'] <= node['normalizedLength'] <= 2000:
            raise ValueError('Malformed native match span')
        if not legacy and (not known_span or not row['confirmationStartedAtMillis'] <= node['validatedAtMillis'] <= row['readFinishedAtMillis']):
            raise ValueError('Native candidate predates its confirmation')
        at = row['readFinishedAtMillis'] if node['validatedAtMillis'] == -1 else node['validatedAtMillis']
        if not row['readStartedAtMillis'] <= at <= row['readFinishedAtMillis'] or node['validUntilMillis'] != min(2**63 - 1, at + row['maximumAgeMillis']):
            raise ValueError('Native candidate borrowed sibling/receipt expiry')
        if node['validUntilMillis'] < row['receivedAtMillis']: raise ValueError('Expired native candidate admitted')
        regions += node['lineCount']
    if regions != row['regionCount'] or regions > 256: raise ValueError('Native raw region count differs')
    return row


def analyze(records, expected_events=None, dropped_events=None):
    if expected_events is not None:
        integer(expected_events)
        if len(records) != expected_events:
            raise ValueError('Raw/manifest event inventory differs')
    if dropped_events is not None:
        integer(dropped_events)
    traces, posts, scrolls, natives, cost_starts, costs, sequences = [], [], [], [], [], [], set()
    selected = 0
    for event in records:
        if type(event) is not dict or set(event) != ENVELOPE:
            raise ValueError('Unknown/incomplete Lab envelope')
        for key in ('sequence', 'elapsedNanos', 'observedUptimeMillis', 'wallMillis'):
            integer(event[key])
        if event['sequence'] in sequences:
            raise ValueError('Duplicate raw event identity')
        sequences.add(event['sequence'])
        if any(type(event[key]) is not str for key in ('thread', 'tag', 'message')):
            raise ValueError('Unsupported Lab text field')
        message = event['message']
        if not message.startswith('REPLACEMENT_'):
            continue
        selected += 1
        if event['tag'] != 'ScreenshotA11y':
            raise ValueError('Replacement producer tag differs')
        prefix, separator, payload = message.partition(' ')
        if not separator or prefix not in ('REPLACEMENT_TRACE', 'REPLACEMENT_POST', 'REPLACEMENT_SCROLL', 'REPLACEMENT_NATIVE', 'REPLACEMENT_NATIVE_COST_START', 'REPLACEMENT_NATIVE_COST'):
            raise ValueError('Unknown selected replacement record')
        row = decode(payload)
        versions=(1,2,3,4) if prefix in ('REPLACEMENT_NATIVE_COST_START','REPLACEMENT_NATIVE_COST','REPLACEMENT_NATIVE') else ((1,2) if prefix == 'REPLACEMENT_SCROLL' else (1,))
        if type(row) is not dict or row.get('schemaVersion') not in versions or type(row['schemaVersion']) is not int or row.get('clockDomain') != 'UPTIME_MILLIS':
            raise ValueError('Unsupported replacement schema/clock')
        integer(row.get('connection'), 1)
        if prefix == 'REPLACEMENT_NATIVE_COST_START':
            cost_starts.append(native_cost_start(row, event['observedUptimeMillis']))
        elif prefix == 'REPLACEMENT_NATIVE_COST':
            costs.append(native_cost_row(row, event['observedUptimeMillis']))
        elif prefix == 'REPLACEMENT_TRACE':
            if set(row) != TRACE or row['stage'] not in STAGES or row['lane'] not in ('NONE', 'FAST', 'QUALITY', 'TEXT'):
                raise ValueError('Incomplete replacement trace')
            for key in ('epoch', 'imageRequest', 'atMillis', 'queueMillis'):
                integer(row[key])
            for key in ('pixelAgeMillis', 'preprocessMillis', 'runtimeMillis', 'postprocessMillis'):
                integer(row[key], -1)
            work = [row[key] for key in ('preprocessMillis', 'runtimeMillis', 'postprocessMillis')]
            if any(v == -1 for v in work) and work != [-1, -1, -1]:
                raise ValueError('Partially known work clock')
            if row['atMillis'] > event['observedUptimeMillis']:
                raise ValueError('Produced trace is later than same-domain observation')
            traces.append(row)
        elif prefix == 'REPLACEMENT_SCROLL':
            scrolls.append(scroll_row(row, event['observedUptimeMillis']))
        elif prefix == 'REPLACEMENT_NATIVE':
            native_row(row, event['observedUptimeMillis'])
            native_publication(natives, row)
            natives.append(row)
        else:
            if set(row) != POST:
                raise ValueError('Incomplete replacement post')
            for key in ('mainThread', 'acceptedPaint', 'canvasKnown'):
                if type(row[key]) is not bool:
                    raise ValueError('Unsupported post Boolean')
            for key in POST - {'schemaVersion', 'clockDomain', 'canvasKind', 'mainThread', 'acceptedPaint', 'canvasKnown', 'compositorAtMillis', 'pixelAtMillis'}:
                integer(row[key])
            integer(row['pixelAtMillis'], -1)
            if type(row['compositorAtMillis']) is not int or row['compositorAtMillis'] != -1:
                raise ValueError('Receipt cannot supply compositor arrival')
            if row['mainThread'] or not row['recordedAtMillis'] <= row['postReturnedAtMillis'] <= row['uiAdmittedAtMillis'] <= event['observedUptimeMillis']:
                raise ValueError('Post worker/same-domain order differs')
            if row['canvasKind'] not in ('NONE', 'IMAGE', 'NATIVE_TEXT') or row['canvasKnown'] != (row['canvasKind'] != 'NONE'):
                raise ValueError('Unknown canvas provenance')
            if row['canvasKnown'] and not row['acceptedPaint']:
                raise ValueError('Unaccepted post cannot have an accepted canvas')
            if not row['canvasKnown'] and any(row[key] != 0 for key in ('epoch', 'sequence', 'geometry', 'imageRequest')):
                raise ValueError('Unknown canvas fabricated an identity')
            if not row['canvasKnown'] and row['pixelAtMillis'] != -1:
                raise ValueError('Unknown canvas fabricated pixel time')
            if row['canvasKind'] == 'IMAGE' and (row['sequence'] <= 0 or row['imageRequest'] <= 0):
                raise ValueError('Image canvas request lineage missing')
            if row['canvasKind'] == 'NATIVE_TEXT' and (row['imageRequest'] != 0 or row['pixelAtMillis'] != -1 or row['sequence'] <= 0):
                raise ValueError('Native canvas fabricated image clock')
            if not row['acceptedPaint'] and row['memberCount'] != 0:
                raise ValueError('Unaccepted post invented painted members')
            posts.append(row)
    reconcile_native_costs(cost_starts, costs, natives)
    parsed = len(traces) + len(posts) + len(scrolls) + len(natives) + len(cost_starts) + len(costs)
    if selected != parsed:
        raise ValueError('Selected raw/parsed publication inventory differs')
    stages = Counter(row['stage'] for row in traces)
    return dict(schemaVersion=1, rawEvents=len(records), manifestEvents=expected_events,
        droppedEvents=dropped_events, rawSelectedPublications=selected, parsedSelectedPublications=parsed,
        selectedParsingComplete=True, overallCollectionComplete=dropped_events == 0 and expected_events == len(records),
        traceRows=len(traces), postRows=len(posts), scrollRows=len(scrolls), nativeRows=len(natives), stages=dict(stages),
        nativeCostStartRows=len(cost_starts), nativeCostTerminalRows=len(costs), nativeCostCollectionAvailable=bool(cost_starts),
        nativeCostCoverageComplete=bool(cost_starts) and dropped_events==0 and expected_events==len(records),
        nativeCostTerminals=dict(Counter(row['terminal'] for row in costs)),
        completeNativeRows=sum(row['coverage'] == 'COMPLETE_TREE' for row in natives),
        positiveNodeOnlyRows=sum(row['coverage'] == 'POSITIVE_NODES_ONLY' for row in natives),
        nativeReadModes=dict(Counter(row.get('readMode','UNKNOWN') for row in natives)),
        nativeReadModeProvenanceComplete=bool(natives) and all(row['schemaVersion'] in (2,3,4) for row in natives)
            and bool(costs) and all(row['schemaVersion'] in (3,4) for row in costs),
        nativeObservationRows=sum(row.get('observationOrdinal',0)>0 for row in natives),
        nativeTerminalAdmissionRows=sum(row['schemaVersion'] in (3,4) and row['observationOrdinal']==0 for row in natives),
        nativeLegacyAdmissionRows=sum(row['schemaVersion']<3 for row in natives),
        nativeObservationProvenanceComplete=bool(natives) and all(row['schemaVersion'] in (3,4) for row in natives),
        actualNativeAcquisitionStarts=sum(row['readStartedAtMillis']>=0 for row in costs),
        successfulNativeAcquisitionTerminals=sum(row['terminal']=='SUCCESS' for row in costs),
        nativeCandidates=sum(row['candidateCount'] for row in natives),
        scrollAdmissions=dict(Counter(row['admission'] for row in scrolls)),
        scrollRefusals=dict(Counter(row['refusal'] for row in scrolls)),
        scrollOwnerKinds=dict(Counter(row['ownerKind'] for row in scrolls if row['schemaVersion'] == 2)),
        missingScrollOwnerKindRows=sum(row['schemaVersion'] == 1 for row in scrolls),
        scrollOwnerKindProvenanceComplete=bool(scrolls) and all(row['schemaVersion'] == 2 for row in scrolls),
        unknownScrollAxisRows=sum(any(not row[axis]['absoluteKnown'] and not row[axis]['deltaKnown'] for axis in ('x', 'y')) for row in scrolls),
        missingScrollReadRows=sum(row['readStartedAtMillis'] == -1 and row['readFinishedAtMillis'] == -1 for row in scrolls),
        unknownPixelAgeRows=sum(row['pixelAgeMillis'] == -1 for row in traces),
        unknownWorkRows=sum(row['runtimeMillis'] == -1 for row in traces),
        acceptedNonemptyPosts=sum(row['acceptedPaint'] and row['memberCount'] > 0 for row in posts),
        compositorArrivalKnown=False, physicalMotionQualified=False, qualityQualified=False,
        pixelPerformanceAcceptance=False)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('trace', type=Path)
    parser.add_argument('--manifest', type=Path, required=True)
    args = parser.parse_args()
    manifest = decode(args.manifest.read_text(encoding='utf-8'))
    records = [decode(line) for line in args.trace.read_text(encoding='utf-8').splitlines() if line.strip()]
    result = analyze(records, manifest['eventCount'], manifest['droppedEvents'])
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
