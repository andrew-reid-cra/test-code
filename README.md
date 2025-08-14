# test-code
##
#Step 1 capture 
##
rm -r /data/prot/queue/cap-out
java -Xms256m -Xmx1g -XX:+UseG1GC -cp "lib/*:out" sniffer.capture.CaptureMain \
  iface=ens2d1 bufmb=256 snap=65535 timeout=1 \
  bpf="(tcp and net 7.33.161.0/24) or (vlan and tcp and net 7.33.161.0/24)" \
  out=/data/prot/queue/cap-out base=cap rollMiB=512

##
# tool to look for a needle in the haystack.  you can confirm you saw something in the capture out before proceeding
##
#java -cp "lib/*:out" sniffer.tools.SegbinGrep in=/data/prot/queue/cap-out \
#  needle=<string to look for - needle in the haystack> ctx=48


##
# step 2 assemble http streams
##
#rm -rf /data/prot/queue/http-out
#java -Xms512m -Xmx8g -XX:+UseG1GC -cp "lib/*:out" sniffer.assemble.AssembleMain -Dsniffer.negCacheLog2=18\
#       	-Dsniffer.bodyBufCap=65536 -Dsniffer.evictEvery=65536 -Dsniffer.midstream=true in=/data/prot/queue/cap-out out=/data/prot/queue/http-out


##
# Step 3 - pair request / responses - assign unique ids to pairings - todo add sessioning
##
#rm -rf //data/prot/queue/final-out
# java -Xms1g -Xmx8g -XX:+UseG1GC -cp "lib/*:out" sniffer.poster.PosterMain \
#  --in /data/prot/radar/queue/http-out \
#  --out /data/prot/queue/final-out \
#  --workers 32 \
#  --decode all \
#  --maxOpenBlobs 256
