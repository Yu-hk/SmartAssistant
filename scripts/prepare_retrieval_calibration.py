"""Compile only the standalone calibration probe; no model or network calls."""
import argparse
import os
from pathlib import Path
import subprocess
import zipfile


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath-file',required=True,type=Path)
    parser.add_argument('--common-classes',required=True,type=Path)
    parser.add_argument('--product-classes',required=True,type=Path)
    parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--javac',default='javac')
    args=parser.parse_args()
    args.output.mkdir(parents=True,exist_ok=True)
    paths=[args.common_classes.resolve(),args.product_classes.resolve()]+[Path(s).resolve() for s in args.classpath_file.read_text(encoding='utf-8').strip().split(os.pathsep)]
    line='Class-Path: '+' '.join(path.as_uri()+('/' if path.is_dir() else '') for path in paths)
    manifest='Manifest-Version: 1.0\r\n'
    while line:
        manifest+=line[:70]+'\r\n'; line=' '+line[70:] if len(line)>70 else ''
    jar=args.output/'classpath.jar'
    with zipfile.ZipFile(jar,'w') as archive: archive.writestr('META-INF/MANIFEST.MF',manifest+'\r\n')
    subprocess.run([args.javac,'-proc:none','-encoding','UTF-8','-cp',str(jar),'-d',str(args.output),
                    'scripts/java/RetrievalCalibrationProbe.java',
                    'scripts/java/NativeRetrievalCalibrationProbe.java'],check=True)
    with zipfile.ZipFile(args.output/'calibration-probe.zip','w',zipfile.ZIP_DEFLATED) as archive:
        for pattern in ('RetrievalCalibrationProbe*.class','NativeRetrievalCalibrationProbe*.class'):
            for path in args.output.glob(pattern): archive.write(path,path.name)


if __name__=='__main__': main()
