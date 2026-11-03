// SPDX-License-Identifier: Apache-2.0
package crawlercommons.urlfrontier.service.rocksdb;
import crawlercommons.urlfrontier.Urlfrontier.*;
import java.util.Map;
/** Run with baseline fat JAR first on classpath, against a fresh test-only DB. */
public final class LegacyFixture {
 public static void main(String[] args)throws Exception {
  try(RocksDBService s=new RocksDBService(Map.of("rocksdb.path",args[0]),"localhost",7071)){
   if(args.length>1 && args[1].equals("create"))
    if(s.putURLItem(URLItem.newBuilder().setDiscovered(DiscoveredURLItem.newBuilder().setInfo(URLInfo.newBuilder().setCrawlID("legacy").setKey("legacy").setUrl("http://legacy/seed"))).build())!=AckMessage.Status.OK)throw new AssertionError("legacy seed failed");
  }
 }
}
