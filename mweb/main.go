package main

import "C"

import "github.com/ltcmweb/mwebd"

var servers = map[int]*mwebd.Server{}

//export start
func start(chain, dataDir, proxy *C.char) C.int {
	args := &mwebd.ServerArgs{
		Chain:     C.GoString(chain),
		DataDir:   C.GoString(dataDir),
		ProxyAddr: C.GoString(proxy),
	}
	// Regtest has no DNS seeds, so connect to the local node's default regtest port
	if args.Chain == "regtest" {
		args.PeerAddr = "127.0.0.1:19444"
	}
	server, err := mwebd.NewServer2(args)
	if err != nil {
		return 0
	}
	port, err := server.Start(0)
	if err != nil {
		return 0
	}
	servers[port] = server
	return C.int(port)
}

//export stop
func stop(port C.int) {
	if server, ok := servers[int(port)]; ok {
		server.Stop()
		delete(servers, int(port))
	}
}

func main() {}
