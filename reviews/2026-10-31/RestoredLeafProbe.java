import java.lang.reflect.*;
import org.minima.database.MinimaDB;
import org.minima.database.txpowtree.*;
import org.minima.objects.base.*;
import org.minima.objects.keys.*;
import org.minima.system.commands.base.block;
import org.minima.system.params.GeneralParams;
import org.minima.utils.BIP39;
import org.minima.utils.Crypto;

/** Disposable public fixture. No wallet files, RPC, chain connection or real funds. */
public class RestoredLeafProbe {
  static class MemoryDB extends MinimaDB { @Override public void saveUserDB() {} }
  static Field field(Class<?> c,String n)throws Exception {Field f=c.getDeclaredField(n);f.setAccessible(true);return f;}
  static void tip(MinimaDB db,int height)throws Exception {
    Constructor<TxPoWTreeNode> c=TxPoWTreeNode.class.getDeclaredConstructor();c.setAccessible(true);
    TxPoWTreeNode node=c.newInstance();field(TxPoWTreeNode.class,"mBlockNumber").set(node,new MiniNumber(height));
    field(TxPowTree.class,"mTip").set(db.getTxPoWTree(),node);
  }
  public static void main(String[]args)throws Exception {
    GeneralParams.USE_BLOCK_AS_KEYUSES=true;
    MemoryDB db=new MemoryDB();field(MinimaDB.class,"mMinimaDB").set(null,db);
    MiniData seed=Crypto.getInstance().hashObjects(BIP39.convertStringToSeed("Public disposable PandaPools review fixture v1"),new MiniData(java.math.BigInteger.ZERO));
    TreeKey old=TreeKey.createDefault(seed);
    // Multiple signatures in one block can move the persisted selector past the chain height.
    tip(db,2399999);db.getUserDB().setLastBlockAsKeyUses(new MiniNumber(2399999));
    int oldIndex=block.getCurrentBlockAsKeyUses(2399999,old.getMaxUses()).getAsInt();
    old.setUses(oldIndex);Signature prior=old.sign(new MiniData("0x01"));
    // Original node is now stopped. Seed restore loses both persisted counters; chain advances one block.
    TreeKey restored=TreeKey.createDefault(seed);tip(db,2400000);db.getUserDB().setLastBlockAsKeyUses(MiniNumber.ZERO);
    int restoredIndex=block.getCurrentBlockAsKeyUses(0,restored.getMaxUses()).getAsInt();
    restored.setUses(restoredIndex);Signature probe=restored.sign(new MiniData("0x00"));
    var a=prior.getAllSignatureProofs();var b=probe.getAllSignatureProofs();
    boolean sameLeaf=a.get(a.size()-1).getPublicKey().isEqual(b.get(b.size()-1).getPublicKey());
    boolean valid=old.verify(new MiniData("0x01"),prior)&&restored.verify(new MiniData("0x00"),probe);
    System.out.println("REVIEW oldIndex="+oldIndex+" restoredIndex="+restoredIndex+" sameOneTimePublicKey="+sameLeaf+" signaturesVerify="+valid+" readbackUses="+restored.getUses());
    if(oldIndex!=restoredIndex||!sameLeaf||!valid)throw new AssertionError("Counterexample did not reproduce");
  }
}
