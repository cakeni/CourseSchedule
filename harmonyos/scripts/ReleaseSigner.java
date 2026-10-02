import com.ohos.entity.Mode;
import com.ohos.entity.ProFileSigned;
import com.ohos.entity.RetMsg;
import com.ohos.entity.SignAppParameters;
import com.ohos.entity.SignCode;
import com.ohos.hapsigntool.HapSignTool;
import com.ohos.hapsigntool.error.ERROR;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

// Keep passwords out of process arguments and the vendor tool's console logs.
public final class ReleaseSigner {
    public static void main(String[] args) throws Exception {
        if (args.length != 7) throw new IllegalArgumentException("Expected seven public signing parameters");
        byte[] input = System.in.readAllBytes();
        int length = input.length;
        while (length > 0 && (input[length - 1] == '\r' || input[length - 1] == '\n')) length--;
        if (length == 0 || length > 1024) throw new IllegalArgumentException("Missing signing password");
        char[] password = new String(input, 0, length, StandardCharsets.UTF_8).toCharArray();
        Arrays.fill(input, (byte) 0);
        PrintStream output = System.out;
        PrintStream errors = System.err;
        int status = 1;
        try (PrintStream muted = new PrintStream(OutputStream.nullOutputStream())) {
            System.setOut(muted);
            System.setErr(muted);
            SignAppParameters parameters = new SignAppParameters();
            parameters.setMode(Mode.LOCAL_SIGN);
            parameters.setKeyAlias(args[0]);
            parameters.setKeyStoreFile(args[1]);
            parameters.setAppCertFile(args[2]);
            parameters.setProfileFile(args[3]);
            parameters.setInFile(args[4]);
            parameters.setOutFile(args[5]);
            parameters.setCompatibleVersion(args[6]);
            parameters.setSignAlg("SHA256withECDSA");
            parameters.setProfileSigned(ProFileSigned.SIGNED);
            parameters.setSignCode(SignCode.OPEN);
            parameters.setKeystorePwd(password);
            parameters.setKeyPwd(password);
            RetMsg result = HapSignTool.signApp(parameters);
            if (result.getErrCode() == ERROR.SUCCESS_CODE) {
                output.println("Official signing completed.");
                status = 0;
            } else {
                errors.println("Official signing failed: " + result.getErrMessage().replace(new String(password), "<redacted>"));
            }
        } catch (Exception error) {
            errors.println("Official signing failed: " + String.valueOf(error.getMessage()).replace(new String(password), "<redacted>"));
        } finally {
            System.setOut(output);
            System.setErr(errors);
            Arrays.fill(password, '\0');
        }
        System.exit(status);
    }
}
