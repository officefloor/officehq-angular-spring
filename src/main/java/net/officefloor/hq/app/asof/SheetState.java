package net.officefloor.hq.app.asof;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Every row kept for a sheet, table by table, as it stood at one moment. */
public record SheetState(List<Table> tables) implements Serializable {

    /** A table's rows kept for the sheet: the values of its stored (not generated) columns. */
    public record Table(String name, List<String> columns, List<Object[]> rows) implements Serializable {
    }

    public byte[] toBytes() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(new GZIPOutputStream(bytes))) {
            out.writeObject(this);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    public static SheetState of(byte[] data) {
        try (ObjectInputStream in = new ObjectInputStream(new GZIPInputStream(new ByteArrayInputStream(data)))) {
            return (SheetState) in.readObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The digest of the stored state, which tells two states apart. */
    public static String digest(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
