#!/usr/bin/env python3
"""Fail if standalone's shared recovery engine differs from Pandamonium's tested implementation."""
from pathlib import Path
import sys
root=Path(__file__).resolve().parents[1]
donor=Path(sys.argv[1]) if len(sys.argv)>1 else root.parent/'base/integrated/pandapools'
names='Recovery RestoreExit ExitAuthority ExitStore ExitTicket BlockOwnerRecovery ReserveRecovery ArchiveNode MegaArchive MegaArchiveProofs CollectSweeper PoolManager TxPost PendingCollect CollectConfirmation CollectionEvidence'.split()
for name in names:
    relative=Path('src/main/java/com/eurobuddha/pandapools')/(name+'.java')
    if (root/'app'/relative).read_bytes() != (donor/relative).read_bytes():
        raise SystemExit('Recovery parity drift: '+name)
print(str(len(names))+' recovery engine files match Pandamonium exactly.')
