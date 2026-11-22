import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 打 macOS 用的 zip：每個項目帶 Unix 權限（目錄與 *.command 為 755，其他 644），macOS 的「封存工具程式」解壓後
 * .command 才有執行權限。Compress-Archive 不寫 Unix 權限。寫完逐項讀回中央目錄確認「建立系統＝Unix」與權限。
 * 用法：UnixZip <來源資料夾> <zip>
 */
public final class UnixZip {
    public static void main(String[] args) throws IOException {
        Path src = Path.of(args[0]).toAbsolutePath();
        Path zip = Path.of(args[1]).toAbsolutePath();
        Files.deleteIfExists(zip);
        FileTime time = FileTime.fromMillis(946684800000L); // 2000-01-01Z；zip 存本地時間，同一時區建置才逐位元組相同
        List<Path> files;
        try (Stream<Path> s = Files.walk(src)) {
            files = s.filter(p -> !p.equals(src)).sorted().toList();
        }
        try (FileSystem fs = FileSystems.newFileSystem(URI.create("jar:" + zip.toUri()), Map.of("create", "true", "enablePosixFileAttributes", "true"))) {
            for (Path p : files) {
                Path e = fs.getPath(src.relativize(p).toString().replace('\\', '/'));
                if (Files.isDirectory(p)) Files.createDirectories(e);
                else Files.write(e, Files.readAllBytes(p));
                Files.setPosixFilePermissions(e, PosixFilePermissions.fromString(mode(p)));
                Files.setLastModifiedTime(e, time);
            }
        }
        addFileTypes(zip);
        verify(zip, src, files);
        System.out.println("UnixZip OK: " + files.size() + " entries -> " + zip);
    }

    static String mode(Path p) {
        return Files.isDirectory(p) || p.getFileName().toString().endsWith(".command") ? "rwxr-xr-x" : "rw-r--r--";
    }

    /** zipfs 只寫權限位元，沒有檔案型別（unzip -Z 顯示 ?rwx）；補上 S_IFDIR／S_IFREG，解壓工具才不必猜。中央目錄沒有 CRC，可以原地改。 */
    static void addFileTypes(Path zip) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(zip)).order(ByteOrder.LITTLE_ENDIAN);
        int eocd = eocd(b);
        int pos = b.getInt(eocd + 16);
        for (int k = Short.toUnsignedInt(b.getShort(eocd + 10)); k > 0; k--) {
            int nameLen = Short.toUnsignedInt(b.getShort(pos + 28));
            boolean dir = b.get(pos + 46 + nameLen - 1) == '/';
            int mode = (b.getInt(pos + 38) >>> 16) & 0777;
            b.putInt(pos + 38, ((dir ? 0040000 : 0100000) | mode) << 16 | (dir ? 0x10 : 0));
            pos += 46 + nameLen + Short.toUnsignedInt(b.getShort(pos + 30)) + Short.toUnsignedInt(b.getShort(pos + 32));
        }
        Files.write(zip, b.array());
    }

    static int eocd(ByteBuffer b) {
        int eocd = b.limit() - 22;
        while (eocd >= 0 && b.getInt(eocd) != 0x06054b50) eocd--;
        if (eocd < 0) throw new IllegalStateException("no end of central directory");
        return eocd;
    }

    /** 讀中央目錄：每個項目 version made by 的高位元組＝3（Unix），external attributes 高 16 位＝檔案型別＋權限。 */
    static void verify(Path zip, Path src, List<Path> files) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(zip)).order(ByteOrder.LITTLE_ENDIAN);
        int eocd = eocd(b);
        int count = Short.toUnsignedInt(b.getShort(eocd + 10));
        int pos = b.getInt(eocd + 16);
        if (count != files.size()) throw new IllegalStateException("entries " + count + " != " + files.size());
        for (int k = 0; k < count; k++) {
            if (b.getInt(pos) != 0x02014b50) throw new IllegalStateException("bad central directory at " + pos);
            int madeBy = Short.toUnsignedInt(b.getShort(pos + 4)) >> 8;
            int nameLen = Short.toUnsignedInt(b.getShort(pos + 28));
            int extraLen = Short.toUnsignedInt(b.getShort(pos + 30));
            int commentLen = Short.toUnsignedInt(b.getShort(pos + 32));
            int mode = b.getInt(pos + 38) >>> 16;
            byte[] name = new byte[nameLen];
            b.get(pos + 46, name);
            String n = new String(name, java.nio.charset.StandardCharsets.UTF_8);
            Path local = src.resolve(n.endsWith("/") ? n.substring(0, n.length() - 1) : n);
            int want = (Files.isDirectory(local) ? 0040000 : 0100000) | Integer.parseInt(mode(local).equals("rwxr-xr-x") ? "755" : "644", 8);
            if (madeBy != 3 || mode != want) {
                throw new IllegalStateException(n + ": madeBy=" + madeBy + " mode=" + Integer.toOctalString(mode));
            }
            pos += 46 + nameLen + extraLen + commentLen;
        }
    }
}
