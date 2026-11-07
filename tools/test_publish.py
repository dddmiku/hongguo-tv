import copy
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/"tools"))
import publish

class PublishTest(unittest.TestCase):
    def test_tv_feed_is_independent_and_can_bootstrap_without_pc(self):
        (ROOT/"_work").mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=ROOT/"_work") as name:
            apk=Path(name)/"hongguo-tv-1.1.3.APK";apk.write_bytes(b"signed-test-placeholder")
            result=publish.feed("1.1.3",14,apk,"TV notes")
        self.assertEqual(result["version"],"1.1.3")
        self.assertEqual(set(result["platforms"]),{"android-tv"})
        tv=result["platforms"]["android-tv"]
        self.assertEqual(tv["versionCode"],14)
        self.assertEqual(tv["size"],len(b"signed-test-placeholder"))
        self.assertEqual(tv["sha256"],result["sha256"])
        self.assertEqual(tv["url"],"https://github.com/dddmiku/hongguo-tv/releases/download/v1.1.3/hongguo-tv-1.1.3.APK")
    def test_public_assets_are_immutable(self):
        with self.assertRaisesRegex(RuntimeError,"已公开"):publish.require_draft({"draft":False})
        publish.require_draft({"draft":True})
    def test_invalid_version_and_filename_cannot_publish(self):
        with self.assertRaises(ValueError):publish.feed("1.1.3",14,Path("wrong.APK"),"")
        with self.assertRaises(ValueError):publish.feed("bad",14,Path("unused.APK"),"")
        with self.assertRaises(ValueError):publish.feed("1.1.3",True,Path("unused.APK"),"")
    def test_app_and_publisher_use_only_new_tv_repository(self):
        source=(ROOT/"app/src/main/java/cn/hongguo/tv/update/Updater.java").read_text(encoding="utf-8")
        self.assertIn('REPO = "dddmiku/hongguo-tv"',source)
        self.assertNotIn("hongguo-desktop-releases",source)
        self.assertEqual(publish.REPO,"dddmiku/hongguo-tv")

if __name__=="__main__":unittest.main()
