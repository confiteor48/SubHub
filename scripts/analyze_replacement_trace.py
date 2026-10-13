"""Strict selected replacement telemetry reader. No compositor/physical/quality verdict."""
import argparse
import json
from pathlib import Path
from collections import Counter

ENVELOPE = {'sequence', 'elapsedNanos', 'observedUptimeMillis', 'wallMillis', 'thread', 'tag', 'message'}
TRACE = {'schemaVersion', 'clockDomain', 'connection', 'stage', 'epoch', 'imageRequest', 'lane', 'atMillis', 'queueMillis',
         'pixelAgeMillis', 'preprocessMillis', 'runtimeMillis', 'postprocessMillis'}
POST = {'schemaVersion', 'clockDomain', 'connection', 'endpointGeneration', 'postSerial', 'recordedAtMillis', 'postReturnedAtMillis',
        'uiAdmittedAtMillis', 'compositorAtMillis', 'queuedNanos', 'lockNanos', 'recordingNanos', 'postNanos',
        'mainThread', 'acceptedPaint', 'memberCount', 'canvasKnown', 'canvasKind', 'epoch', 'sequence',
        'geometry', 'imageRequest', 'pixelAtMillis'}
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


def analyze(records, expected_events=None, dropped_events=None):
    if expected_events is not None:
        integer(expected_events)
        if len(records) != expected_events:
            raise ValueError('Raw/manifest event inventory differs')
    if dropped_events is not None:
        integer(dropped_events)
    traces, posts, sequences = [], [], set()
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
        if not separator or prefix not in ('REPLACEMENT_TRACE', 'REPLACEMENT_POST'):
            raise ValueError('Unknown selected replacement record')
        row = decode(payload)
        if type(row) is not dict or row.get('schemaVersion') != 1 or type(row['schemaVersion']) is not int or row.get('clockDomain') != 'UPTIME_MILLIS':
            raise ValueError('Unsupported replacement schema/clock')
        integer(row.get('connection'), 1)
        if prefix == 'REPLACEMENT_TRACE':
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
    if selected != len(traces) + len(posts):
        raise ValueError('Selected raw/parsed publication inventory differs')
    stages = Counter(row['stage'] for row in traces)
    return dict(schemaVersion=1, rawEvents=len(records), manifestEvents=expected_events,
        droppedEvents=dropped_events, rawSelectedPublications=selected, parsedSelectedPublications=len(traces)+len(posts),
        selectedParsingComplete=True, overallCollectionComplete=dropped_events == 0 and expected_events == len(records),
        traceRows=len(traces), postRows=len(posts), stages=dict(stages),
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
