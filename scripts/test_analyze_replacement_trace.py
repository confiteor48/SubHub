import copy
import json
import unittest
from scripts import analyze_replacement_trace as reader


class ReplacementTraceReaderTest(unittest.TestCase):
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


if __name__=='__main__':unittest.main()
