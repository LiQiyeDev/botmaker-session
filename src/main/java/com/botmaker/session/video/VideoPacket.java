package com.botmaker.session.video;

/**
 * One H.264 <b>access unit</b> in Annex-B framing — the bytes of exactly one coded picture, start codes
 * included, ready to hand to a decoder as a single unit.
 *
 * <p>Access units rather than NAL units because that is the granularity every consumer actually wants:
 * WebCodecs' {@code VideoDecoder.decode} takes one {@code EncodedVideoChunk} per picture, and a WebSocket
 * message per NAL would put the parameter sets and the slice they describe in separate frames for no gain.
 *
 * @param annexB   the access unit's bytes, each NAL preceded by its {@code 00 00 01} / {@code 00 00 00 01}
 *                 start code, exactly as the encoder wrote them
 * @param keyframe whether a decoder that has seen <b>nothing</b> before this packet can start here — which is
 *                 deliberately stricter than "contains an IDR". An IDR slice alone is undecodable without the
 *                 SPS/PPS that describe it, and a client joining a running stream has never seen them, so this
 *                 is true only for an access unit carrying its own parameter sets <em>and</em> an IDR. Encoders
 *                 repeat the parameter sets before each IDR in Annex-B output, so the stricter reading costs
 *                 nothing in practice and makes "may this client start here?" a question with one answer.
 */
public record VideoPacket(byte[] annexB, boolean keyframe) {
}
