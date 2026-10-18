import copy
import json
import unittest
from scripts import analyze_replacement_trace as reader


class ReplacementTraceReaderTest(unittest.TestCase):
    def cohort_records(self, terminal='SUCCESS', final=True):
        rows = self.streaming_records(terminal, final)
        for event in rows:
            prefix, _, payload = event['message'].partition(' '); body = reader.decode(payload)
            body.update(schemaVersion=4, readMode=reader.COHORT_MODE)
            if prefix == 'REPLACEMENT_NATIVE':
                body.update(coverage='POSITIVE_NODES_ONLY', visitedNodes=2, classifiedNodes=2)
            event['message'] = prefix + ' ' + json.dumps(body)
        return rows

    def test_cohort_two_nodes_and_early_group_are_one_actual_partial_acquisition(self):
        rows = self.cohort_records(); report = reader.analyze(rows, len(rows), 0)
        self.assertEqual((2, 1, 1), (report['nativeRows'], report['nativeObservationRows'], report['nativeTerminalAdmissionRows']))
        self.assertEqual((1, 1), (report['actualNativeAcquisitionStarts'], report['successfulNativeAcquisitionTerminals']))
        self.assertEqual((0, 2), (report['completeNativeRows'], report['positiveNodeOnlyRows']))
        self.assertEqual({reader.COHORT_MODE: 2}, report['nativeReadModes'])
        self.assertTrue(report['nativeReadModeProvenanceComplete']); self.assertTrue(report['nativeObservationProvenanceComplete'])
        self.assertEqual(report['rawSelectedPublications'], report['parsedSelectedPublications'])
        self.assertFalse(report['pixelPerformanceAcceptance'])

    def test_cohort_failed_second_member_keeps_only_finite_original_observation(self):
        for terminal in ('FAILURE', 'CANCELLED', 'RESOURCE_FAILURE'):
            rows = self.cohort_records(terminal, False); report = reader.analyze(rows, len(rows), 0)
            self.assertEqual((1, 0, 0), (report['nativeObservationRows'], report['nativeTerminalAdmissionRows'],
                                       report['successfulNativeAcquisitionTerminals']))
            self.assertEqual({terminal: 1}, report['nativeCostTerminals'])

    def test_cohort_cannot_omit_second_terminal_member_or_fabricate_tree_or_ordinal(self):
        for index, field, value in ((3, 'candidateCount', 1), (3, 'confirmationReads', 1),
                (3, 'visitedNodes', 1), (3, 'classifiedNodes', 3), (3, 'coverage', 'COMPLETE_TREE'),
                (1, 'observationOrdinal', 2), (1, 'observationOrdinal', True), (1, 'candidateCount', 2),
                (1, 'confirmationReads', 2), (1, 'visitedNodes', True), (1, 'newField', 0)):
            rows = self.cohort_records(); prefix, _, payload = rows[index]['message'].partition(' ')
            body = reader.decode(payload); body[field] = value; rows[index]['message'] = prefix + ' ' + json.dumps(body)
            with self.subTest(index=index, field=field), self.assertRaises(ValueError): reader.analyze(rows, len(rows), 0)

    def test_cohort_requires_its_new_schema_at_every_parent_and_source_boundary(self):
        for index in range(4):
            for field, value in (('schemaVersion', 3), ('schemaVersion', 5), ('readMode', 'FULL_DISCOVERY'),
                                 ('readMode', 'ADMITTED_NODE_RECLASSIFY')):
                rows = self.cohort_records(); prefix, _, payload = rows[index]['message'].partition(' ')
                body = reader.decode(payload); body[field] = value; rows[index]['message'] = prefix + ' ' + json.dumps(body)
                with self.subTest(index=index, field=field, value=value), self.assertRaises(ValueError):
                    reader.analyze(rows, len(rows), 0)

    def streaming_records(self, terminal='SUCCESS', final=True):
        start, end, last = self.mode_records(False)
        body = reader.decode(last['message'].partition(' ')[2])
        body.update(schemaVersion=3, observationOrdinal=0, coverage='COMPLETE_TREE', confirmationGeneration=1, sequence=4)
        last.update(sequence=4, message='REPLACEMENT_NATIVE '+json.dumps(body))
        early = copy.deepcopy(last)
        first = copy.deepcopy(body)
        first.update(observationOrdinal=1, sequence=3, readFinishedAtMillis=200, receivedAtMillis=210,
                     coverage='POSITIVE_NODES_ONLY', confirmationReads=1, candidateCount=1, regionCount=1,
                     nodes=first['nodes'][:1])
        early.update(sequence=2, observedUptimeMillis=210, message='REPLACEMENT_NATIVE '+json.dumps(first))
        cost = reader.decode(end['message'].partition(' ')[2]); cost['terminal'] = terminal
        start['sequence'] = 1
        end.update(sequence=3, message='REPLACEMENT_NATIVE_COST '+json.dumps(cost))
        return [start, early, end] + ([last] if final else [])

    def test_early_group_and_real_terminal_are_one_acquisition_not_two_reads(self):
        rows = self.streaming_records(); report = reader.analyze(rows, len(rows), 0)
        self.assertEqual((2, 1, 1), (report['nativeRows'], report['nativeObservationRows'], report['nativeTerminalAdmissionRows']))
        self.assertEqual((1, 1), (report['actualNativeAcquisitionStarts'], report['successfulNativeAcquisitionTerminals']))
        self.assertEqual((1, 1), (report['completeNativeRows'], report['positiveNodeOnlyRows']))
        self.assertTrue(report['nativeObservationProvenanceComplete'])
        self.assertEqual(report['rawSelectedPublications'], report['parsedSelectedPublications'])
        self.assertFalse(report['pixelPerformanceAcceptance'])

    def test_finite_observation_can_precede_genuine_failed_or_cancelled_parent_terminal(self):
        for terminal in ('FAILURE', 'CANCELLED', 'RESOURCE_FAILURE'):
            rows = self.streaming_records(terminal, False); report = reader.analyze(rows, len(rows), 0)
            self.assertEqual((1, 0, 0), (report['nativeObservationRows'], report['nativeTerminalAdmissionRows'],
                                       report['successfulNativeAcquisitionTerminals']))
            self.assertEqual({terminal: 1}, report['nativeCostTerminals'])

    def test_observation_cannot_hide_missing_or_foreign_actual_parent_terminal(self):
        for missing in ('start', 'terminal', 'both', 'foreign'):
            rows = self.streaming_records(final=False)
            if missing == 'start': rows.pop(0)
            elif missing == 'terminal': rows.pop(2)
            elif missing == 'both': rows = rows[1:2]
            else:
                body = reader.decode(rows[2]['message'].partition(' ')[2]); body['request'] += 1
                rows[2]['message'] = 'REPLACEMENT_NATIVE_COST '+json.dumps(body)
            with self.subTest(missing=missing), self.assertRaises(ValueError): reader.analyze(rows, len(rows), 0)

    def test_optional_groups_cannot_fabricate_terminal_tree_direct_or_multi_group_authority(self):
        for field, value in (('observationOrdinal', True), ('observationOrdinal', -1), ('observationOrdinal', 65),
                             ('observationOrdinal', 2), ('coverage', 'COMPLETE_TREE'), ('readMode', 'ADMITTED_NODE_RECLASSIFY'),
                             ('candidateCount', 2), ('request', -1), ('newField', 0), ('schemaVersion', 4)):
            rows = self.streaming_records(); body = reader.decode(rows[1]['message'].partition(' ')[2]); body[field] = value
            rows[1]['message'] = 'REPLACEMENT_NATIVE '+json.dumps(body)
            with self.subTest(field=field, value=value), self.assertRaises(ValueError): reader.analyze(rows, len(rows), 0)
        rows = self.streaming_records(); body = reader.decode(rows[1]['message'].partition(' ')[2]); del body['observationOrdinal']
        rows[1]['message'] = 'REPLACEMENT_NATIVE '+json.dumps(body)
        with self.assertRaises(ValueError): reader.analyze(rows, len(rows), 0)

    def test_observation_and_terminal_must_keep_exact_parent_clocks_and_order(self):
        for field, value in (('queuedAtMillis', 81), ('startedAtMillis', 91), ('readStartedAtMillis', 101),
                             ('classificationCompletedAtMillis', 121), ('confirmationStartedAtMillis', 169),
                             ('hardGeneration', 2), ('scanGeneration', 0), ('maximumAgeMillis', 1001),
                             ('visitedNodes', 6), ('classifiedNodes', 3), ('sequence', 4),
                             ('receivedAtMillis', 311), ('readFinishedAtMillis', 301)):
            rows = self.streaming_records(); body = reader.decode(rows[1]['message'].partition(' ')[2]); body[field] = value
            rows[1].update(observedUptimeMillis=max(330, value if field in ('receivedAtMillis', 'readFinishedAtMillis') else 0),
                           message='REPLACEMENT_NATIVE '+json.dumps(body))
            with self.subTest(field=field), self.assertRaises(ValueError): reader.analyze(rows, len(rows), 0)

    def test_duplicate_group_or_observation_after_terminal_cannot_add_confirmations(self):
        for after_terminal in (False, True):
            rows = self.streaming_records(); duplicate = copy.deepcopy(rows[1]); duplicate['sequence'] = 5
            body = reader.decode(duplicate['message'].partition(' ')[2]); body['sequence'] = 5
            duplicate['message'] = 'REPLACEMENT_NATIVE '+json.dumps(body)
            rows.insert(len(rows) if after_terminal else 2, duplicate)
            with self.subTest(after_terminal=after_terminal), self.assertRaises(ValueError): reader.analyze(rows, len(rows), 0)

    def test_legacy_schema_keeps_observation_provenance_unknown_not_fabricated_zero_work(self):
        rows = self.mode_records(False); report = reader.analyze(rows, len(rows), 0)
        self.assertFalse(report['nativeObservationProvenanceComplete'])
        self.assertEqual((0, 0, 1), (report['nativeObservationRows'], report['nativeTerminalAdmissionRows'],
                                   report['nativeLegacyAdmissionRows']))

    def mode_records(self, direct=True):
        rows=self.cost_records(); mode='ADMITTED_NODE_RECLASSIFY' if direct else 'FULL_DISCOVERY'
        for event in rows:
            prefix,_,payload=event['message'].partition(' ');body=reader.decode(payload)
            body.update(schemaVersion=2 if prefix=='REPLACEMENT_NATIVE' else 3,readMode=mode)
            if prefix=='REPLACEMENT_NATIVE_COST':
                body['costs']=[dict(stage=s,calls=1,nanos=10,maximumNanos=10,failedCalls=0) for s in reader.COST_STAGES_V2]
                body.update(rawCostCount=9,unattributedNanos=10)
            elif prefix=='REPLACEMENT_NATIVE' and direct:
                body.update(visitedNodes=1,classifiedNodes=1,confirmationReads=1,candidateCount=1,regionCount=1,nodes=body['nodes'][:1])
            event['message']=prefix+' '+json.dumps(body)
        return rows

    def test_actual_direct_and_discovery_modes_match_invocation_terminal_and_source(self):
        for direct in (False,True):
            rows=self.mode_records(direct);report=reader.analyze(rows,len(rows),0)
            self.assertTrue(report['nativeReadModeProvenanceComplete'])
            self.assertEqual({'ADMITTED_NODE_RECLASSIFY' if direct else 'FULL_DISCOVERY':1},report['nativeReadModes'])
        report=reader.analyze(self.cost_records(),3,0)
        self.assertFalse(report['nativeReadModeProvenanceComplete']);self.assertEqual({'UNKNOWN':1},report['nativeReadModes'])

    def test_direct_operation_cannot_be_retagged_as_a_complete_or_multi_node_tree(self):
        for field,value in (('coverage','COMPLETE_TREE'),('visitedNodes',5),('candidateCount',2),('readMode','FUTURE_MODE')):
            rows=self.mode_records();body=reader.decode(rows[-1]['message'].partition(' ')[2]);body[field]=value
            rows[-1]['message']='REPLACEMENT_NATIVE '+json.dumps(body)
            with self.subTest(field=field),self.assertRaises(ValueError): reader.analyze(rows,len(rows),0)

    def test_missing_or_mixed_operation_mode_cannot_hide_actual_acquisition_kind(self):
        for target,mode in ((0,'FULL_DISCOVERY'),(1,'FULL_DISCOVERY'),(2,'FULL_DISCOVERY'),(2,None)):
            rows=self.mode_records();prefix,_,payload=rows[target]['message'].partition(' ');body=reader.decode(payload)
            if mode is None: del body['readMode']
            else: body['readMode']=mode
            rows[target]['message']=prefix+' '+json.dumps(body)
            with self.subTest(target=target,mode=mode),self.assertRaises(ValueError):reader.analyze(rows,len(rows),0)

    def test_current_traversal_bucket_requires_version_matched_invocation_and_terminal(self):
        rows=self.cost_records()
        for event in rows[:2]:
            prefix,_,payload=event['message'].partition(' ');body=reader.decode(payload);body['schemaVersion']=2
            if prefix=='REPLACEMENT_NATIVE_COST':
                body['costs']=[dict(stage=s,calls=1,nanos=10,maximumNanos=10,failedCalls=0) for s in reader.COST_STAGES_V2]
                body.update(rawCostCount=9,unattributedNanos=10)
            event['message']=prefix+' '+json.dumps(body)
        self.assertTrue(reader.analyze(rows,len(rows),0)['nativeCostCoverageComplete'])
        body=reader.decode(rows[0]['message'].partition(' ')[2]);body['schemaVersion']=1
        rows[0]['message']='REPLACEMENT_NATIVE_COST_START '+json.dumps(body)
        with self.assertRaises(ValueError):reader.analyze(rows,len(rows),0)
    def cost_records(self, terminal='SUCCESS', with_native=True):
        native=self.native(); body=reader.decode(native['message'].partition(' ')[2])
        start=dict(schemaVersion=1,clockDomain='UPTIME_MILLIS',connection=5,request=4,epoch=2,geometry=3,
                   hardGeneration=1,invokedAtMillis=90)
        cost=dict(start,scanGeneration=1,readStartedAtMillis=100,readFinishedAtMillis=300,terminalAtMillis=310,
                  terminal=terminal,durationClock='SYSTEM_NANO_TIME',elapsedNanos=100,unattributedNanos=20,rawCostCount=8,
                  costs=[dict(stage=s,calls=1,nanos=10,maximumNanos=10,failedCalls=0) for s in reader.COST_STAGES])
        first=dict(native,sequence=2,observedUptimeMillis=90,message='REPLACEMENT_NATIVE_COST_START '+json.dumps(start))
        last=dict(native,sequence=3,observedUptimeMillis=310,message='REPLACEMENT_NATIVE_COST '+json.dumps(cost))
        return [first,last,native] if with_native else [first,last]

    def test_actual_invocations_and_terminal_costs_reconcile_separately_from_admitted_sources(self):
        rows=self.cost_records(); report=reader.analyze(rows,len(rows),0)
        self.assertTrue(report['nativeCostCoverageComplete']);self.assertEqual(1,report['nativeCostTerminalRows'])
        self.assertEqual(report['rawSelectedPublications'],report['parsedSelectedPublications'])
        rows=self.cost_records('CANCELLED',False);report=reader.analyze(rows,len(rows),0)
        self.assertEqual({'CANCELLED':1},report['nativeCostTerminals']);self.assertEqual(0,report['nativeRows'])

    def test_legacy_collection_cannot_claim_zero_cost_complete_attribution(self):
        report=reader.analyze([self.native()],1,0)
        self.assertFalse(report['nativeCostCollectionAvailable']);self.assertFalse(report['nativeCostCoverageComplete'])

    def test_missing_duplicate_or_foreign_reader_terminal_is_incomplete(self):
        for mode in ('missing','duplicate','foreign'):
            rows=self.cost_records()
            if mode=='missing':rows.pop(1)
            elif mode=='duplicate':rows.append(dict(rows[1],sequence=4))
            else:
                body=reader.decode(rows[1]['message'].partition(' ')[2]);body['request']=77
                rows[1]['message']='REPLACEMENT_NATIVE_COST '+json.dumps(body)
            with self.subTest(mode=mode),self.assertRaises(ValueError):reader.analyze(rows,len(rows),0)

    def test_failed_reader_or_borrowed_read_clock_cannot_qualify_an_admitted_source(self):
        for mode in ('failure','clock','hard'):
            rows=self.cost_records();body=reader.decode(rows[1]['message'].partition(' ')[2])
            if mode=='failure':body['terminal']='FAILURE'
            elif mode=='clock':body['readFinishedAtMillis']-=1
            else:body['hardGeneration']+=1
            rows[1]['message']='REPLACEMENT_NATIVE_COST '+json.dumps(body)
            with self.subTest(mode=mode),self.assertRaises(ValueError):reader.analyze(rows,len(rows),0)

    def test_unknown_stages_nested_time_counts_maxima_and_primitives_are_not_silent_zeroes(self):
        for mode in ('stage','nested','count','max','failed','float','overflow','extra'):
            rows=self.cost_records();body=reader.decode(rows[1]['message'].partition(' ')[2]);bucket=body['costs'][0]
            if mode=='stage':bucket['stage']='NEW_FUTURE_STAGE'
            elif mode=='nested':body['unattributedNanos']+=10
            elif mode=='count':bucket['calls']=0
            elif mode=='max':bucket['maximumNanos']=11
            elif mode=='failed':bucket['failedCalls']=2
            elif mode=='float':body['elapsedNanos']=100.0
            elif mode=='overflow':body['terminalAtMillis']=9223372036854775808
            else:bucket['private_caption']='forbidden'
            rows[1]['message']='REPLACEMENT_NATIVE_COST '+json.dumps(body)
            with self.subTest(mode=mode),self.assertRaises(ValueError):reader.analyze(rows,len(rows),0)

    def test_native_producer_or_source_duplicates_cannot_be_counted_as_more_confirmations(self):
        for same_source in (False, True):
            first = self.native(); second = copy.deepcopy(first); second['sequence'] = 2
            body = reader.decode(second['message'].partition(' ')[2])
            body.update(sequence=3 if same_source else 4, request=5 if same_source else 4)
            second['message'] = 'REPLACEMENT_NATIVE '+json.dumps(body)
            with self.assertRaises(ValueError): reader.analyze([first, second], 2, 0)

    def native(self, partial=True):
        event = self.row(); event['observedUptimeMillis'] = 330
        value = dict(schemaVersion=1, clockDomain='UPTIME_MILLIS', connection=5, epoch=2, sequence=3,
            request=4, queuedAtMillis=80, startedAtMillis=90, readStartedAtMillis=100, readFinishedAtMillis=300,
            receivedAtMillis=320, maximumAgeMillis=1000, coverage='POSITIVE_NODES_ONLY' if partial else 'COMPLETE_TREE',
            scanGeneration=1, confirmationGeneration=2 if partial else 1, hardGeneration=1,
            classificationCompletedAtMillis=120, confirmationStartedAtMillis=168,
            visitedNodes=5, classifiedNodes=2, confirmationReads=2, candidateCount=2, regionCount=3,
            nodes=[dict(validatedAtMillis=190, validUntilMillis=1190, textSource='TEXT', matchStart=0, matchEnd=4,
                        normalizedLength=4, lineCount=1, exactGeometry=True),
                   dict(validatedAtMillis=290, validUntilMillis=1290, textSource='CONTENT_DESCRIPTION', matchStart=1, matchEnd=4,
                        normalizedLength=6, lineCount=2, exactGeometry=False)])
        event['message'] = 'REPLACEMENT_NATIVE '+json.dumps(value)
        return event

    def test_native_partial_and_complete_are_separate_raw_matched_inventories(self):
        partial = self.native(); complete = self.native(False); complete['sequence'] = 2
        body = reader.decode(complete['message'].partition(' ')[2]); body.update(sequence=4, request=5)
        complete['message'] = 'REPLACEMENT_NATIVE '+json.dumps(body)
        result = reader.analyze([partial, complete], 2, 0)
        self.assertEqual((result['nativeRows'], result['positiveNodeOnlyRows'], result['completeNativeRows'], result['nativeCandidates']), (2, 1, 1, 4))
        self.assertEqual(result['rawSelectedPublications'], result['parsedSelectedPublications'])
        self.assertFalse(result['pixelPerformanceAcceptance'])

    def test_native_clocks_spans_expiry_and_counts_cannot_borrow_other_nodes(self):
        for change in ('unknown', 'early', 'delay', 'sibling', 'count', 'future', 'extra', 'job', 'span', 'expired'):
            event = self.native(); body = reader.decode(event['message'].partition(' ')[2])
            if change == 'unknown': body['scanGeneration'] = -1
            elif change == 'early': body['nodes'][0]['validatedAtMillis'] = 110
            elif change == 'delay': body['confirmationStartedAtMillis'] = 167
            elif change == 'sibling': body['nodes'][0]['validUntilMillis'] = 1300
            elif change == 'count': body['candidateCount'] = 1
            elif change == 'future': body['schemaVersion'] = 2
            elif change == 'extra': body['nodes'][0]['caption'] = 'not allowed'
            elif change == 'job': body['startedAtMillis'] = 110
            elif change == 'span': body['nodes'][0]['matchEnd'] = 5
            else: body['maximumAgeMillis'] = 10; body['nodes'][0]['validUntilMillis'] = 200
            event['message'] = 'REPLACEMENT_NATIVE '+json.dumps(body)
            with self.subTest(change=change), self.assertRaises(ValueError): reader.analyze([event], 1, 0)

    def test_typed_complete_cannot_have_cross_revision_or_preconfirmation_candidate(self):
        for change in ('revision', 'candidate', 'unknown_clocks'):
            event = self.native(False); body = reader.decode(event['message'].partition(' ')[2])
            if change == 'revision': body['confirmationGeneration'] = 2
            elif change == 'candidate': body['nodes'][0]['validatedAtMillis'] = 110
            else: body['classificationCompletedAtMillis'] = body['confirmationStartedAtMillis'] = -1
            event['message'] = 'REPLACEMENT_NATIVE '+json.dumps(body)
            with self.assertRaises(ValueError): reader.analyze([event], 1, 0)

    def test_empty_partial_cannot_become_an_admitted_negative_or_source(self):
        event = self.native(); body = reader.decode(event['message'].partition(' ')[2])
        body.update(candidateCount=0, regionCount=0, nodes=[], confirmationStartedAtMillis=-1, confirmationReads=0)
        event['message'] = 'REPLACEMENT_NATIVE '+json.dumps(body)
        with self.assertRaises(ValueError): reader.analyze([event], 1, 0)

    def test_legacy_complete_unknowns_remain_explicit_and_cannot_be_retyped_partial(self):
        event = self.native(False); body = reader.decode(event['message'].partition(' ')[2])
        for key in ('request', 'queuedAtMillis', 'startedAtMillis', 'scanGeneration', 'confirmationGeneration',
                    'hardGeneration', 'classificationCompletedAtMillis', 'confirmationStartedAtMillis'): body[key] = -1
        for node in body['nodes']:
            node.update(validatedAtMillis=-1, validUntilMillis=1300, textSource='UNKNOWN', matchStart=-1, matchEnd=-1, normalizedLength=-1)
        event['message'] = 'REPLACEMENT_NATIVE '+json.dumps(body)
        self.assertEqual(reader.analyze([event], 1, 0)['completeNativeRows'], 1)
        body['coverage'] = 'POSITIVE_NODES_ONLY'; event['message'] = 'REPLACEMENT_NATIVE '+json.dumps(body)
        with self.assertRaises(ValueError): reader.analyze([event], 1, 0)

    def row(self):
        value=dict(schemaVersion=1,clockDomain='UPTIME_MILLIS',connection=5,stage='JOB_COMPLETED',epoch=2,imageRequest=17,
            lane='FAST',atMillis=100,queueMillis=7,pixelAgeMillis=-1,preprocessMillis=3,runtimeMillis=17,postprocessMillis=2)
        return dict(sequence=1,elapsedNanos=200000000,observedUptimeMillis=110,wallMillis=123,thread='worker',
            tag='ScreenshotA11y',message='REPLACEMENT_TRACE '+json.dumps(value))

    def test_exact_inventory_and_unknown_pixel_clock(self):
        result=reader.analyze([self.row()],1,0)
        self.assertEqual((result['rawSelectedPublications'],result['parsedSelectedPublications']),(1,1))
        self.assertEqual(result['unknownPixelAgeRows'],1)
        self.assertFalse(result['pixelPerformanceAcceptance'])

    def test_unknown_selected_format_never_becomes_zero_work(self):
        row=self.row();row['message']='REPLACEMENT_FUTURE {}'
        with self.assertRaises(ValueError):reader.analyze([row],1,0)

    def test_missing_future_and_null_fields_are_rejected(self):
        for change in ('missing','future','null','boolean'):
            row=self.row();body=reader.decode(row['message'].partition(' ')[2])
            if change=='missing':body.pop('pixelAgeMillis')
            elif change=='future':body['schemaVersion']=2
            elif change=='null':body['runtimeMillis']=None
            else:body['runtimeMillis']=True
            row['message']='REPLACEMENT_TRACE '+json.dumps(body)
            with self.assertRaises(ValueError):reader.analyze([row],1,0)

    def test_duplicate_event_and_json_keys_are_rejected(self):
        row=self.row()
        with self.assertRaises(ValueError):reader.analyze([row,copy.deepcopy(row)],2,0)
        row['message']=row['message'].replace('"runtimeMillis": 17','"runtimeMillis": 17, "runtimeMillis": 18')
        with self.assertRaises(ValueError):reader.analyze([row],1,0)

    def test_manifest_count_and_drops_are_not_concealed(self):
        with self.assertRaises(ValueError):reader.analyze([self.row()],2,0)
        self.assertFalse(reader.analyze([self.row()],1,1)['overallCollectionComplete'])
        self.assertFalse(reader.analyze([self.row()])['overallCollectionComplete'])

    def test_clock_mapping_is_not_invented(self):
        row=self.row();row['observedUptimeMillis']=99
        with self.assertRaises(ValueError):reader.analyze([row],1,0)

    def test_truncated_selected_record_is_rejected(self):
        row=self.row();row['message']=row['message'][:-8]
        with self.assertRaises(ValueError):reader.analyze([row],1,0)

    def test_literal_unknown_work_remains_unknown(self):
        row=self.row();body=reader.decode(row['message'].partition(' ')[2])
        for key in ('preprocessMillis','runtimeMillis','postprocessMillis'):body[key]=-1
        row['message']='REPLACEMENT_TRACE '+json.dumps(body)
        self.assertEqual(reader.analyze([row],1,0)['unknownWorkRows'],1)

    def post(self):
        event=self.row()
        value=dict(schemaVersion=1,clockDomain='UPTIME_MILLIS',connection=5,endpointGeneration=1,postSerial=3,
            recordedAtMillis=100,postReturnedAtMillis=101,uiAdmittedAtMillis=105,compositorAtMillis=-1,
            queuedNanos=1000,lockNanos=2000,recordingNanos=3000,postNanos=4000,mainThread=False,
            acceptedPaint=True,memberCount=2,canvasKnown=True,canvasKind='IMAGE',epoch=2,sequence=7,
            geometry=3,imageRequest=17,pixelAtMillis=-1)
        event['message']='REPLACEMENT_POST '+json.dumps(value)
        return event

    def test_actual_post_receipt_keeps_compositor_unknown(self):
        result=reader.analyze([self.post()],1,0)
        self.assertEqual(result['postRows'],1)
        self.assertEqual(result['acceptedNonemptyPosts'],1)
        self.assertFalse(result['compositorArrivalKnown'])

    def test_post_unknown_missing_and_fabricated_clock_are_rejected(self):
        for key,value in (('compositorAtMillis',105),('mainThread',True),('canvasKnown',False),('imageRequest',0),('connection',0)):
            event=self.post();body=reader.decode(event['message'].partition(' ')[2]);body[key]=value
            event['message']='REPLACEMENT_POST '+json.dumps(body)
            with self.assertRaises(ValueError):reader.analyze([event],1,0)
        event=self.post();body=reader.decode(event['message'].partition(' ')[2]);body.pop('memberCount')
        event['message']='REPLACEMENT_POST '+json.dumps(body)
        with self.assertRaises(ValueError):reader.analyze([event],1,0)

    def test_native_canvas_cannot_borrow_image_time(self):
        event=self.post();body=reader.decode(event['message'].partition(' ')[2]);body.update(canvasKind='NATIVE_TEXT',imageRequest=0,pixelAtMillis=-1)
        event['message']='REPLACEMENT_POST '+json.dumps(body)
        self.assertEqual(reader.analyze([event],1,0)['postRows'],1)
        body['pixelAtMillis']=95;event['message']='REPLACEMENT_POST '+json.dumps(body)
        with self.assertRaises(ValueError):reader.analyze([event],1,0)

    def scroll(self):
        event = self.row()
        axis = dict(valid=True, absoluteKnown=False, absolute=None, deltaKnown=True, delta=0)
        value = dict(schemaVersion=1, clockDomain='UPTIME_MILLIS', connection=5, epoch=2, geometry=3,
            windowId=4, protectionOrigin='NORMAL', tapScope=7, delivery=1, owner=8, producer=9,
            sourceAtMillis=100, receivedAtMillis=101, readStartedAtMillis=102, readFinishedAtMillis=102,
            admittedAtMillis=103, admission='BASELINE', refusal='NONE', generation=1, producerCount=1,
            intervalCount=0, x=axis, y=dict(valid=True, absoluteKnown=True, absolute=0, deltaKnown=True, delta=0),
            reportedViewport=dict(left=10., top=20., right=500., bottom=800.),
            pixelPhaseKnown=False, memberApplicabilityKnown=False, physicalErrorKnown=False)
        event['message'] = 'REPLACEMENT_SCROLL ' + json.dumps(value)
        return event

    def altered_scroll(self, **changes):
        event = self.scroll(); body = reader.decode(event['message'].partition(' ')[2]); body.update(changes)
        event['message'] = 'REPLACEMENT_SCROLL ' + json.dumps(body)
        return event

    def test_scroll_raw_parsed_counts_and_advisory_support_are_explicit(self):
        result = reader.analyze([self.scroll()], 1, 0)
        self.assertEqual((result['rawSelectedPublications'], result['parsedSelectedPublications'], result['scrollRows']), (1, 1, 1))
        self.assertEqual(result['scrollAdmissions'], {'BASELINE': 1})
        self.assertFalse(result['physicalMotionQualified'])

    def test_scroll_owner_kinds_preserve_legacy_missing_separately_from_explicit_unknown(self):
        rows = [self.altered_scroll(schemaVersion=2, ownerKind=kind)
                for kind in ('UNKNOWN', 'RECYCLER_VIEW', 'OTHER')]
        for sequence, row in enumerate(rows, 1): row['sequence'] = sequence
        result = reader.analyze(rows, 3, 0)
        self.assertEqual(result['scrollOwnerKinds'], {'UNKNOWN': 1, 'RECYCLER_VIEW': 1, 'OTHER': 1})
        self.assertEqual(result['missingScrollOwnerKindRows'], 0)
        self.assertTrue(result['scrollOwnerKindProvenanceComplete'])
        self.assertEqual(result['rawSelectedPublications'], result['parsedSelectedPublications'])
        legacy = self.scroll(); legacy['sequence'] = 4
        mixed = reader.analyze(rows + [legacy], 4, 0)
        self.assertEqual(mixed['scrollOwnerKinds'], result['scrollOwnerKinds'])
        self.assertEqual(mixed['missingScrollOwnerKindRows'], 1)
        self.assertFalse(mixed['scrollOwnerKindProvenanceComplete'])
        self.assertFalse(mixed['physicalMotionQualified'])
        self.assertFalse(reader.analyze([], 0, 0)['scrollOwnerKindProvenanceComplete'])

    def test_scroll_owner_kind_schema_fields_and_enum_are_strict(self):
        for changes in (dict(schemaVersion=2), dict(ownerKind='UNKNOWN'),
                        dict(schemaVersion=2, ownerKind=None), dict(schemaVersion=2, ownerKind=1),
                        dict(schemaVersion=2, ownerKind='androidx.recyclerview.widget.RecyclerView'),
                        dict(schemaVersion=2, ownerKind='recycler_view'),
                        dict(schemaVersion=3, ownerKind='UNKNOWN')):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                reader.analyze([self.altered_scroll(**changes)], 1, 0)

    def test_scroll_schema_two_does_not_enable_other_record_schema_two(self):
        for event in (self.row(), self.post()):
            prefix, _, payload = event['message'].partition(' ')
            body = reader.decode(payload); body['schemaVersion'] = 2
            event['message'] = prefix + ' ' + json.dumps(body)
            with self.assertRaises(ValueError): reader.analyze([event], 1, 0)

    def test_unknown_axis_is_distinct_from_literal_observed_zero(self):
        unknown = dict(valid=True, absoluteKnown=False, absolute=None, deltaKnown=False, delta=None)
        result = reader.analyze([self.altered_scroll(x=unknown)], 1, 0)
        self.assertEqual(result['unknownScrollAxisRows'], 1)
        self.assertEqual(reader.analyze([self.scroll()], 1, 0)['unknownScrollAxisRows'], 0)
        unknown['delta'] = 0
        with self.assertRaises(ValueError): reader.analyze([self.altered_scroll(x=unknown)], 1, 0)

    def test_missing_scroll_read_keeps_explicit_gap_and_unknown_clocks(self):
        event = self.altered_scroll(owner=0, producer=0, readStartedAtMillis=-1, readFinishedAtMillis=-1,
            admission='GAP', refusal='EXPLICIT_GAP', producerCount=0, reportedViewport=None)
        result = reader.analyze([event], 1, 0)
        self.assertEqual(result['missingScrollReadRows'], 1)
        self.assertEqual(result['scrollRefusals'], {'EXPLICIT_GAP': 1})

    def test_invalid_original_scroll_clock_is_preserved_only_in_refusal(self):
        event = self.altered_scroll(sourceAtMillis=-7, admission='GAP', refusal='CLOCK', producerCount=0)
        self.assertEqual(reader.analyze([event], 1, 0)['scrollRefusals'], {'CLOCK': 1})
        for changes in (dict(sourceAtMillis=-7), dict(readFinishedAtMillis=111), dict(admittedAtMillis=111),
                        dict(admission='GAP', refusal='CLOCK', producerCount=0)):
            with self.assertRaises(ValueError): reader.analyze([self.altered_scroll(**changes)], 1, 0)

    def test_scroll_unknown_missing_null_and_future_fields_fail_closed(self):
        for change in ('extra', 'missing', 'null', 'future'):
            event = self.scroll(); body = reader.decode(event['message'].partition(' ')[2])
            if change == 'extra': body['memberSupport'] = True
            elif change == 'missing': body.pop('producer')
            elif change == 'null': body['generation'] = None
            else: body['schemaVersion'] = 3
            event['message'] = 'REPLACEMENT_SCROLL ' + json.dumps(body)
            with self.assertRaises(ValueError): reader.analyze([event], 1, 0)

    def test_scroll_cannot_grant_support_or_accept_invalid_coordinates(self):
        for changes in (dict(pixelPhaseKnown=True), dict(memberApplicabilityKnown=True), dict(physicalErrorKnown=0),
                        dict(owner=0), dict(admission='UNKNOWN', refusal='NONE'),
                        dict(x=dict(valid=False, absoluteKnown=False, absolute=None, deltaKnown=True, delta=0)),
                        dict(reportedViewport=dict(left=10, top=20, right=10, bottom=800))):
            with self.assertRaises(ValueError): reader.analyze([self.altered_scroll(**changes)], 1, 0)

    def test_selected_trace_post_scroll_counts_all_reconcile(self):
        rows = [self.row(), self.post(), self.scroll()]
        for sequence, row in enumerate(rows, 1): row['sequence'] = sequence
        result = reader.analyze(rows, 3, 0)
        self.assertEqual((result['traceRows'], result['postRows'], result['scrollRows']), (1, 1, 1))
        self.assertEqual(result['rawSelectedPublications'], result['parsedSelectedPublications'])
        with self.assertRaises(ValueError): reader.analyze(rows, 4, 0)

    def test_scroll_gap_does_not_hide_interval_or_producer_state(self):
        for changes in (dict(admission='GAP', refusal='EXPLICIT_GAP'),
                        dict(admission='GAP', refusal='EXPLICIT_GAP', producerCount=0, intervalCount=1),
                        dict(admission='GAP', refusal='NONE', producerCount=0)):
            with self.assertRaises(ValueError): reader.analyze([self.altered_scroll(**changes)], 1, 0)


if __name__=='__main__':unittest.main()
