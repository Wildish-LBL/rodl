require 'net/http'
require 'choice'

IVY=true
if IVY then
	BASE_URI="ivy.man.poznan.pl"
	PORT=80
	APP_NAME="rosrs2"
	ADMIN_LOGIN="wfadmin"
	ADMIN_PASSWORD="wfadmin!!!"
else 
	BASE_URI="localhost"
	PORT=8081
	APP_NAME="ro-srs"
	ADMIN_LOGIN="wfadmin"
	ADMIN_PASSWORD="wfadmin!!!"
end







WORKSPACE_ID="rubyUser"
PASSWORD="pass"

RO_NAME="ro1"
VERSION_NAME="ver1"
VERSION_2_NAME="ver2"

FILE1_NAME="file1.txt"
FILE1_PATH="file1.txt"

FILE2_NAME="file2.txt"
FILE2_PATH="dir/file_a-2.txt"
FILE2_DIRECTORY="dir"

MESSAGE_WIDTH=50
code = 200

def printResponse(response, expectedCode)
		puts response.code + " " + response.message
    if Choice.choices[:printHeaders]
      puts response.to_hash
    end   
		if response.code.to_i == expectedCode
			puts response.body if Choice.choices[:printBody] and expectedCode != 204#NO CONTENT
		else
			puts response.body if Choice.choices[:printErrors]
		end
		
end

def printConstantWidth(message) 
	print message
	(MESSAGE_WIDTH - message.size).times {
		print "."
	}
end

Choice.options do
	header 'Options:'
	
	option :printBody do
		short '-p'
		long '--print-body'
		desc 'If set, bodies of correct responses will be printed'
		default false
	end
	
	option :printErrors do
		short '-e'
		long '--print-errors'
		desc 'If set, error messages will be printed'
		default false
	end
	
	option :printHeaders do
    short '-H'
    long '--print-headers'
    desc 'If set, HTTP headers will be printed'
    default false
  end
end

def createWorkspace
	Net::HTTP.start(BASE_URI, PORT) {|http|
		printConstantWidth "Creating workspace........"
		req = Net::HTTP::Post.new('/' + APP_NAME + '/workspaces')
		req.basic_auth ADMIN_LOGIN, ADMIN_PASSWORD
		req.body = WORKSPACE_ID + "
" + PASSWORD
		req.add_field "Content-Type", "text/plain"

		response = http.request(req)
		printResponse(response, 201)
		code = response.code.to_i 
    }
end
	
def createRO
	Net::HTTP.start(BASE_URI, PORT) {|http|
		printConstantWidth "Creating research object........"
		req = Net::HTTP::Post.new('/' + APP_NAME+ '/workspaces/' + WORKSPACE_ID + '/ROs')
		req.basic_auth WORKSPACE_ID, PASSWORD
        req.body = RO_NAME
		req.add_field "Content-Type", "text/plain"

		response = http.request(req)
		printResponse(response, 201)
		code = response.code.to_i 
    }
end

def createVersion
	Net::HTTP.start(BASE_URI, PORT) {|http|
		printConstantWidth "Creating version........"
		req = Net::HTTP::Post.new('/' + APP_NAME + '/workspaces/' + WORKSPACE_ID + '/ROs/' + RO_NAME)
		req.basic_auth WORKSPACE_ID, PASSWORD
        req.body = VERSION_NAME
        req.add_field "Content-Type", "text/plain"

		response = http.request(req)
		printResponse(response, 201)
		code = response.code.to_i 
	}
end
		
def addFile1
	Net::HTTP.start(BASE_URI, PORT) {|http|
		printConstantWidth "Adding file1........"
		req = Net::HTTP::Post.new('/' + APP_NAME + '/workspaces/' + WORKSPACE_ID + '/ROs/' + RO_NAME + '/' + VERSION_NAME + '/' + FILE1_PATH)
		req.basic_auth WORKSPACE_ID, PASSWORD
		req.body = File.read(FILE1_NAME)
		req.add_field "Content-Type", "text/plain"

		response = http.request(req)
		printResponse(response, 200)
		code = response.code.to_i 
	}
end

def addFile2
	Net::HTTP.start(BASE_URI, PORT) {|http|
		printConstantWidth "Adding file2........"
		req = Net::HTTP::Post.new('/' + APP_NAME + '/workspaces/' + WORKSPACE_ID + '/ROs/' + RO_NAME + '/' + VERSION_NAME + '/' + FILE2_PATH)
		req.basic_auth WORKSPACE_ID, PASSWORD
		req.body = File.read(FILE2_NAME)
		req.add_field "Content-Type", "text/plain"

		response = http.request(req)
		printResponse(response, 200)
		code = response.code.to_i 
	}
end

def getListRO
	#get list of research objects
	Net::HTTP.start(BASE_URI, PORT) {|http|
		printConstantWidth "Retrieving list of research objects........"
		req = Net::HTTP::Get.new('/' + APP_NAME + '/workspaces/' + WORKSPACE_ID + '/ROs')
		req.basic_auth WORKSPACE_ID, PASSWORD
		response = http.request(req)
		printResponse(response, 200)
	}
end

def getROrdf
	#get research object rdf	
	Net::HTTP.start(BASE_URI, PORT) {|http|
		printConstantWidth "Retrieving research object description........"
		req = Net::HTTP::Get.new('/' + APP_NAME + '/workspaces/' + WORKSPACE_ID + '/ROs/' + RO_NAME)
		req.basic_auth WORKSPACE_ID, PASSWORD
		response = http.request(req)
		printResponse(response, 200)
	}
end
		
def getVersionRdf
	#get version rdf
	Net::HTTP.start(BASE_URI, PORT) {|http|
		printConstantWidth "Retrieving version description........"
		req = Net::HTTP::Get.new('/' + APP_NAME + '/workspaces/' + WORKSPACE_ID + '/ROs/' + RO_NAME + '/' + VERSION_NAME)
		req.basic_auth WORKSPACE_ID, PASSWORD
		req.add_field "Accept", "application/rdf+xml"
		response = http.request(req)
		printResponse(response, 200)
	}
end

def getVersionZip
	#get version zip
	Net::HTTP.start(BASE_URI, PORT) {|http|
		printConstantWidth "Retrieving version archive........"
		req = Net::HTTP::Get.new('/' + APP_NAME + '/workspaces/' + WORKSPACE_ID + '/ROs/' + RO_NAME + '/' + VERSION_NAME + '?content=true')
		req.basic_auth WORKSPACE_ID, PASSWORD
		req.add_field "Accept", "application/zip"
		response = http.request(req)
		puts response.code + " " + response.message
		#no body printing -- binary file!
		if response.code.to_i == 200
			puts "Archive size: " + response["content-length"] if Choice.choices[:printBody]
		else
			puts response.body if Choice.choices[:printErrors]
		end
	}	
end
	
def getManifest				
	#get manifest
	Net::HTTP.start(BASE_URI, PORT) {|http|
		printConstantWidth "Retrieving manifest........"
		req = Net::HTTP::Get.new('/' + APP_NAME + '/workspaces/' + WORKSPACE_ID + '/ROs/' + RO_NAME + '/' + VERSION_NAME)
		req.basic_auth WORKSPACE_ID, PASSWORD
		response = http.request(req)
		printResponse(response, 200)
	}	
end
				
def getFile1Metadata				
	Net::HTTP.start(BASE_URI, PORT) {|http|
			printConstantWidth "Retrieving file1 metadata........"
			req = Net::HTTP::Get.new('/' + APP_NAME + '/workspaces/' + WORKSPACE_ID + '/ROs/' + RO_NAME + '/' + VERSION_NAME + '/' + FILE1_PATH)
			req.basic_auth WORKSPACE_ID, PASSWORD
			
			response = http.request(req)
			printResponse(response, 200)
	}	
end

def getFile2Metadata
	Net::HTTP.start(BASE_URI, PORT) {|http|
			printConstantWidth "Retrieving file2 metadata........"
			req = Net::HTTP::Get.new('/' + APP_NAME + '/workspaces/' + WORKSPACE_ID + '/ROs/' + RO_NAME + '/' + VERSION_NAME + '/' + FILE2_PATH)
			req.basic_auth WORKSPACE_ID, PASSWORD
			
			response = http.request(req)
			printResponse(response, 200)
	}	
end

def getFile1
	Net::HTTP.start(BASE_URI, PORT) {|http|
			printConstantWidth "Retrieving file1 content........"
			req = Net::HTTP::Get.new('/' + APP_NAME + '/workspaces/' + WORKSPACE_ID + '/ROs/' + RO_NAME + '/' + VERSION_NAME + '/' + FILE1_PATH + '?content=true')
			req.basic_auth WORKSPACE_ID, PASSWORD

			response = http.request(req)
			printResponse(response, 200)
	}	
end

def getFile2
	Net::HTTP.start(BASE_URI, PORT) {|http|
			printConstantWidth "Retrieving file2 content........"
			req = Net::HTTP::Get.new('/' + APP_NAME + '/workspaces/' + WORKSPACE_ID + '/ROs/' + RO_NAME + '/' + VERSION_NAME + '/' + FILE2_PATH + '?content=true')
			req.basic_auth WORKSPACE_ID, PASSWORD

			response = http.request(req)
			printResponse(response, 200)
	}	
end

def getDirectoryList
	#get list of files in /dir
	Net::HTTP.start(BASE_URI, PORT) {|http|
			printConstantWidth "Retrieving list of files in a directory........"
			req = Net::HTTP::Get.new('/' + APP_NAME + '/workspaces/' + WORKSPACE_ID + '/ROs/' + RO_NAME + '/' + VERSION_NAME + '/' + FILE2_DIRECTORY)
			req.basic_auth WORKSPACE_ID, PASSWORD
			req.add_field "Accept", "application/xml+rdf"

			response = http.request(req)
			printResponse(response, 200)
	}	
end

def getDirectoryZipped
	#get zipped files in /dir
	Net::HTTP.start(BASE_URI, PORT) {|http|
			printConstantWidth "Retrieving zipped content of a directory........"
			req = Net::HTTP::Get.new('/' + APP_NAME + '/workspaces/' + WORKSPACE_ID + '/ROs/' + RO_NAME + '/' + VERSION_NAME + '/' + FILE2_DIRECTORY + "?content=true")
			req.basic_auth WORKSPACE_ID, PASSWORD
			req.add_field "Accept", "application/zip"

			response = http.request(req)
			printResponse(response, 200)
	}	
end

def updateFile1
	Net::HTTP.start(BASE_URI, PORT) {|http|
		printConstantWidth "Updating file1........"
		req = Net::HTTP::Post.new('/' + APP_NAME + '/workspaces/' + WORKSPACE_ID + '/ROs/' + RO_NAME + '/' + VERSION_NAME + '/' + FILE1_PATH)
		req.basic_auth WORKSPACE_ID, PASSWORD
		req.body = File.read(FILE1_NAME)
		req.add_field "Content-Type", "text/plain"

		response = http.request(req)
		printResponse(response, 200)
	}
end

def updateFile2
	Net::HTTP.start(BASE_URI, PORT) {|http|
		printConstantWidth "Updating file2........"
		req = Net::HTTP::Post.new('/' + APP_NAME + '/workspaces/' + WORKSPACE_ID + '/ROs/' + RO_NAME + '/' + VERSION_NAME + '/' + FILE2_PATH)
		req.basic_auth WORKSPACE_ID, PASSWORD
		req.body = File.read(FILE2_NAME)
		req.add_field "Content-Type", "text/plain"

		response = http.request(req)
		printResponse(response, 200)
	}
end
				
def updateManifest
	Net::HTTP.start(BASE_URI, PORT) {|http|
		printConstantWidth "Updating manifest........"
		req = Net::HTTP::Post.new('/' + APP_NAME + '/workspaces/' + WORKSPACE_ID + '/ROs/' + RO_NAME + '/' + VERSION_NAME)
		req.basic_auth WORKSPACE_ID, PASSWORD
		req.body = File.read("manifest.rdf")
		req.add_field "Content-Type", "application/rdf+xml"

		response = http.request(req)
		printResponse(response, 200)
	}
end

def createVersionAsCopy				
	#create version as a copy of another version
	Net::HTTP.start(BASE_URI, PORT) {|http|
		printConstantWidth "Creating version basing on another........"
		req = Net::HTTP::Post.new('/' + APP_NAME + '/workspaces/' + WORKSPACE_ID + '/ROs/' + RO_NAME)
		req.body = VERSION_2_NAME + "
http://" + BASE_URI + ':' + PORT.to_s + '/' + APP_NAME + '/workspaces/' + WORKSPACE_ID + '/ROs/' + RO_NAME + '/' + VERSION_NAME
		req.add_field "Content-Type", "text/plain"
		req.basic_auth WORKSPACE_ID, PASSWORD

		response = http.request(req)
		printResponse(response, 201)
		
	}
end
				
def deleteFile1
	Net::HTTP.start(BASE_URI, PORT) {|http|
		printConstantWidth "Deleting file1........"
		req = Net::HTTP::Delete.new('/' + APP_NAME + '/workspaces/' + WORKSPACE_ID + '/ROs/' + RO_NAME + '/' + VERSION_NAME + '/' + FILE1_PATH)
		req.basic_auth WORKSPACE_ID, PASSWORD

		response = http.request(req)
		printResponse(response, 204)
	}
end

def deleteFile2
	Net::HTTP.start(BASE_URI, PORT) {|http|
		printConstantWidth "Deleting file2........"
		req = Net::HTTP::Delete.new('/' + APP_NAME + '/workspaces/' + WORKSPACE_ID + '/ROs/' + RO_NAME + '/' + VERSION_NAME + '/' + FILE2_PATH)
		req.basic_auth WORKSPACE_ID, PASSWORD

		response = http.request(req)
		printResponse(response, 204)
	}
end
			
def deleteVersion
	Net::HTTP.start(BASE_URI, PORT) {|http|
		printConstantWidth "Deleting version........"
		req = Net::HTTP::Delete.new('/' + APP_NAME + '/workspaces/' + WORKSPACE_ID + '/ROs/' + RO_NAME + '/' + VERSION_NAME)
		req.basic_auth WORKSPACE_ID, PASSWORD

		response = http.request(req)
		printResponse(response, 204)
	}
end

def deleteRO
	Net::HTTP.start(BASE_URI, PORT) {|http|
		printConstantWidth "Deleting research object........"
		req = Net::HTTP::Delete.new('/' + APP_NAME + '/workspaces/' + WORKSPACE_ID + '/ROs/' + RO_NAME)
		req.basic_auth WORKSPACE_ID, PASSWORD

		response = http.request(req)
		printResponse(response, 204)
	}		
end
	
def deleteWorkspace
	Net::HTTP.start(BASE_URI, PORT) {|http|
		printConstantWidth "Deleting workspace........"
		req = Net::HTTP::Delete.new('/' + APP_NAME + '/workspaces/' + WORKSPACE_ID)
		req.basic_auth ADMIN_LOGIN, ADMIN_PASSWORD
		response = http.request(req)
		printResponse(response, 204)
	}
end

# Tidy up
begin  
  deleteVersion
rescue 
end
begin
  deleteRO
rescue 
end
begin
  deleteWorkspace
rescue
end

if createWorkspace == 201
	if createRO == 201
		if createVersion == 201
			if addFile1 == 200 && addFile2 == 200
				getListRO
				getROrdf
				getVersionRdf
				getVersionZip
				getManifest
				getFile1Metadata
				getFile2Metadata
				getFile1
				getFile2
				getDirectoryList
				getDirectoryZipped
				updateFile1
				updateFile2
				updateManifest
				createVersionAsCopy
				deleteFile1
				deleteFile2
			end
			deleteVersion
		end
		deleteRO
	end
	deleteWorkspace
end
