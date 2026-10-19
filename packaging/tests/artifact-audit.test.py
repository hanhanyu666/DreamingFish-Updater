import importlib.util
import io
from pathlib import Path
import unittest
import zipfile

spec=importlib.util.spec_from_file_location('distribution_audit',Path(__file__).parents[1]/'audit-distributions.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
class LauncherAudit(unittest.TestCase):
    def inspect(self,data):
        output=io.BytesIO()
        with zipfile.ZipFile(output,'w') as archive:archive.writestr('dfs-admin',data)
        issues=[]
        with zipfile.ZipFile(output) as archive:audit.inspect(archive,'dfs-admin-linux-x64-fixture.zip',issues,[])
        return issues
    def test_linux_crlf_and_bom_are_rejected(self):
        self.assertTrue(self.inspect(b'#!/usr/bin/env sh\r\nexit 0\r\n'))
        self.assertTrue(self.inspect(b'\xef\xbb\xbf#!/usr/bin/env sh\nexit 0\n'))
    def test_linux_lf_is_accepted(self):
        self.assertEqual([],self.inspect(b'#!/usr/bin/env sh\nexit 0\n'))
if __name__=='__main__':unittest.main()
