package cn.hongguo.tv.api;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackUrlCodecTest {
    private static final String VALUE="qAABANVfcIVUZRdUx1ObFAuPH08bpaHgfffYg/eROnQszbhAe5HAzxHfFLjnDp9rvLiuNw==";
    private static final String SEED="c2VlZHNlZWRzZWVkc2VlZHNlZWRzZWVkc2VlZHNlZWQ=";
    @Test public void decodesTheOfficialPlaybackEnvelope() {
        assertEquals("https://cdn.example.com/compatible.mp4",PlaybackUrlCodec.decode(VALUE,SEED));
    }
    @Test public void rejectsMalformedEnvelopes() {
        assertNull(PlaybackUrlCodec.decode("not-base64!","bad-seed"));
        assertNull(PlaybackUrlCodec.decode(VALUE,"short"));
        assertNull(PlaybackUrlCodec.decode(null,SEED));
    }
}
