"""Compile the standalone JVM probe and create a portable Java argument file for tests.
Classpath manifest URLs avoid Windows Unicode/command-length problems. No network calls.
"""
import argparse
import os
from pathlib import Path
import subprocess
import zipfile

def main():
    p=argparse.ArgumentParser();p.add_argument('--classpath-file',required=True,type=Path)
    p.add_argument('--classes',required=True,type=Path);p.add_argument('--output',required=True,type=Path)
    p.add_argument('--javac',default='javac');args=p.parse_args()
    args.output.mkdir(parents=True,exist_ok=True)
    paths=[args.classes.resolve()]+[Path(s).resolve() for s in args.classpath_file.read_text(encoding='utf-8').strip().split(os.pathsep)]
    manifest='Manifest-Version: 1.0\r\n'
    line='Class-Path: '+' '.join(s.as_uri()+('/' if s.is_dir() else '') for s in paths)
    while line:
        manifest+=line[:70]+'\r\n';line=' '+line[70:] if len(line)>70 else ''
    manifest+='\r\n'
    jar=args.output/'classpath.jar'
    with zipfile.ZipFile(jar,'w') as z:z.writestr('META-INF/MANIFEST.MF',manifest)
    subprocess.run([args.javac,'-proc:none','-encoding','UTF-8','-cp',str(jar),'-d',str(args.output),'scripts/java/RagEvaluationProbe.java'],check=True)
    (args.output/'java.args').write_text('-cp\n"'+str(args.output.resolve()).replace('\\','/')+os.pathsep+str(jar.resolve()).replace('\\','/')+'"\nRagEvaluationProbe\n',encoding='utf-8')
    with zipfile.ZipFile(args.output/'probe.zip','w',zipfile.ZIP_DEFLATED) as z:
        for path in args.output.glob('RagEvaluationProbe*.class'):z.write(path,path.name)
if __name__=='__main__': main()
