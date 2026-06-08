-- Decode an MP3 on this computer's disk to a WAV, off-thread (mode B).
-- Put mp3dec.wasm in <config>/wasm-modules/ and a song.mp3 in this computer's
-- "media" folder. wasm.run yields the coroutine until the decode finishes, so it
-- never stalls the server tick even for a long file.

local ok, res = wasm.run("mp3dec", {
  args    = { "mp3dec", "/song.mp3", "/song.wav" }, -- argv; "/" is the media mount
  fs      = "media",                                -- mount <computer>/media at "/"
  timeout = 30,
})
if not ok then error("decode failed: " .. tostring(res)) end
print(("exit %d  %s"):format(res.exit, res.stdout))

-- Read the decoded WAV back with the normal CC fs API.
local f = fs.open("media/song.wav", "rb")
local wav = f.readAll(); f.close()
print(("wrote %d bytes of WAV"):format(#wav))
