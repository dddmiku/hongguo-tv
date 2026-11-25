import hashlib
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
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
    def test_release_notes_select_only_the_current_version(self):
        """The published body must describe this build, not the whole history."""
        from pathlib import Path
        import tempfile
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "release-notes.md"
            path.write_text(
                "红果客厅 TV 1.3.9 · 2026-11-26 · 当前（ARM64）\n\n- 本版内容\n\n"
                "红果客厅 TV 1.3.8 · 2026-11-26 · 旧版（ARM64）\n\n- 旧版内容\n", encoding="utf-8")
            notes = publish.current_release_notes(path, "1.3.9")
            self.assertIn("本版内容", notes)
            self.assertNotIn("旧版内容", notes)
            self.assertNotIn("1.3.8", notes)
            with self.assertRaises(RuntimeError):
                publish.current_release_notes(path, "1.3.7")
    def test_public_assets_are_immutable(self):
        with self.assertRaisesRegex(RuntimeError,"已公开"):publish.require_draft({"draft":False})
        publish.require_draft({"draft":True})

    def test_interrupted_draft_is_found_when_tag_lookup_returns_404(self):
        draft = {"id": 101, "tag_name": "v1.3.4", "draft": True}
        with patch.object(publish, "request", side_effect=[(404, {}), (200, [draft])]) as request:
            self.assertEqual(publish.find_release("v1.3.4", "fixture"), draft)
        self.assertEqual(request.call_count, 2)

    def test_draft_asset_requires_digest_and_uploaded_state(self):
        (ROOT / "_work").mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=ROOT / "_work") as directory:
            asset = Path(directory) / "latest.json"
            data = b'{"version":"1.3.4"}'
            asset.write_bytes(data)
            uploaded = {"name": asset.name, "size": len(data), "state": "uploaded",
                        "digest": "sha256:" + hashlib.sha256(data).hexdigest()}
            with self.assertRaisesRegex(RuntimeError, "完整校验"):
                publish.verified_assets({"assets": [{**uploaded, "digest": None}]}, [asset])
            publish.verified_assets({"assets": [uploaded]}, [asset])

    def test_dirty_source_stops_publish_before_credentials_are_loaded(self):
        with patch.object(publish.subprocess, "check_output", return_value=b" M app/build.gradle"):
            with patch.object(publish, "token") as credentials:
                with self.assertRaisesRegex(RuntimeError, "未提交"):
                    publish.publish("a" * 40)
        credentials.assert_not_called()
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
