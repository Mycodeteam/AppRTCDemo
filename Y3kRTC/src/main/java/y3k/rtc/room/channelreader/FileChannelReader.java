package y3k.rtc.room.channelreader;

import org.webrtc.DataChannel;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class FileChannelReader extends ChannelReader<File> {
    private final File file;
    private final FileOutputStream fileOutputStream;

    public FileChannelReader(DataChannel dataChannel, File file) throws FileNotFoundException {
        super(dataChannel);
        this.file = file;
        this.fileOutputStream = new FileOutputStream(file);
    }

    @Override
    protected OutputStream onCreateOutStream() {
        return this.fileOutputStream;
    }

    @Override
    protected File onChannelClosed() {
        try {
            this.fileOutputStream.getFD().sync();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return this.file;
    }
}
