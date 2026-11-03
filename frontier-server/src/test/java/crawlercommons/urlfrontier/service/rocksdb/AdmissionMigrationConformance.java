// SPDX-License-Identifier: Apache-2.0
package crawlercommons.urlfrontier.service.rocksdb;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.rocksdb.*;
/** Tests only newly created disposable baseline databases, never user volumes. */
public final class AdmissionMigrationConformance {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static int baseline(Path db,String jar,boolean create)throws Exception {
  List<String> cmd=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin/java").toString(),"--enable-native-access=ALL-UNNAMED","-cp",jar+java.io.File.pathSeparator+System.getProperty("java.class.path"),LegacyFixture.class.getName(),db.toString()));if(create)cmd.add("create");
  Process p=new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(db.getParent().resolve(create?"baseline-create.log":"baseline-rollback.log").toFile()).start();check(p.waitFor(20,TimeUnit.SECONDS),"baseline child timeout");return p.exitValue();
 }
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]);Files.createDirectories(root);Path db=root.resolve("db");check(baseline(db,args[1],true)==0,"baseline fixture");
  try{new RocksDBService(Map.of("rocksdb.path",db.toString()),"localhost",7071);throw new AssertionError("implicit migration");}catch(IllegalArgumentException expected){}
  try(Options o=new Options()){check(RocksDB.listColumnFamilies(o,db.toString()).size()==3,"refusal changed schema");}
  try(RocksDBService s=new RocksDBService(Map.of("rocksdb.path",db.toString(),"admission.migrate","copy-only"),"localhost",7071)){
   check(AdmissionConformance.dequeue(s,"legacy").size()==1,"migration lost legacy URL");
   AdmissionConformance.base="http://"+AdmissionConformance.host();s.admissionCommand(AdmissionConformance.open("newsession",2,1));s.admissionCommand(AdmissionConformance.reserve("newsession","/seed","",0));
  }
  check(baseline(db,args[1],false)!=0,"stock rollback unexpectedly compatible");
  try(RocksDBService s=new RocksDBService(Map.of("rocksdb.path",db.toString(),"admission.readonly","true"),"localhost",7071)){
   AdmissionConformance.noMutation(s,"newsession",1);check(AdmissionConformance.result(s.admissionCommand(AdmissionConformance.reserve("newsession","/seed","",0))).equals("ADMISSION_READ_ONLY"),"readonly admission");check(AdmissionConformance.dequeue(s,"newsession").size()==1,"compatible rollback lost queue");
  }
  System.out.println("ADMISSION_MIGRATION_PASS explicit-copy-upgrade legacy-preserved stock-rollback-refused compatible-readonly-preserves-protection");
 }
}
