package com.so.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Provider;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.encoders.Hex;

public class EncryptionUtils {
	private static final String SECURITY_PROVIDER_BOUNCY_CASTLE = "BC";

	static {
		Security.addProvider(new BouncyCastleProvider());
	}
	/**
	 * 获取加密密钥的工具类，
	 * @param algorithm 算法名称
	 * @param message 要加密的字符串
	 * @return
	 */
	public static String getkeyByAlgorithm(String algorithm,String message){
//		MessageDigester digester =  new MessageDigester();
		if (algorithm==null||algorithm.trim().equals("")) {
			throw new RuntimeException("算法名称不能为空");
		}
		if (message==null||message.equals("")) {
			throw new RuntimeException("要加密的内容不能为空");
		}
		try {
			return getDigest(algorithm, message).toUpperCase();
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		} catch (NoSuchProviderException e) {
			e.printStackTrace();
		}
		return null;
	}

	
	/**
	 * To get all available Message Digest algorithms for the provider
	 * 
	 * @return all available Digest Algorithms
	 */
	private static List<String> getDigestAlgorithms() {
		Provider provider = Security.getProvider(SECURITY_PROVIDER_BOUNCY_CASTLE);
		List<String> algorithmList = new ArrayList<String>();
		
		for (Object keyObject : provider.keySet()) {
			String key = (String) keyObject;
			
			if (key.startsWith("MessageDigest.")) {
				String algorithm = key.substring("MessageDigest.".length());
				algorithmList.add(algorithm);
            }
		}
		
		return algorithmList;
	}
	
	public static void main(String[] args) {
		List<String> digestAlgorithms = getDigestAlgorithms();
		System.out.println(digestAlgorithms);
	}
	
	/**
	 * To get Message Digest of the supplied input string using the supplied algorithm.
	 * 
	 * @param algorithm
	 * @param message
	 * @return Message Digest of the supplied input string
	 * @throws NoSuchAlgorithmException
	 * @throws NoSuchProviderException
	 */
	private static String getDigest(String algorithm, String message) throws NoSuchAlgorithmException, NoSuchProviderException {
		 MessageDigest messageDigest = MessageDigest.getInstance(algorithm, SECURITY_PROVIDER_BOUNCY_CASTLE);
		 messageDigest.reset();
		 messageDigest.update(message.getBytes());
		
		return Hex.toHexString(messageDigest.digest());
	}
	/*		箭头前为算法的名称
	SHA3-512-->9f87e8a1f05151b16d779d5c1c52e829758d65298be3e2e8018ce344c30d3a1fa0619482d82f29a18d902a4607e8264c68a1476b47d4b78b504d8d0d6b100e70
	RIPEMD160-->62b71d82adc4d0c4d2ed27c8bc4b0885c13cd864
	RIPEMD128-->34326deb11f493bdf72e29f3de9c764f
	Skein-512-256-->390488aa213c01a042b1dc356f5732fc28306c24643fc93ee978a68db649fb76
	WHIRLPOOL-->b7bb82141f50122414ca483f653c938c7d170346f10f0bf8c902911c2219e72ae9ee49dfd2c5d83d078d6b2f4eeb2efa4fdca747a38637c9bc66a03f054c754c
	Skein-512-224-->80ce65f65815a128137b96de5b58376f0cab8f529d6eca38f503996a
	Skein-1024-1024-->b75cd8fc5b3d9642f091c21efe0ae75fd5fcb0f2c626ce4401922c77103ffe93e01cb170bdde02d0d4f32653e31438a6f89b0edaa8bd5e1924026ba650321959d38342cfa54b395c8e54c3f0c35143b3aba3b8da6f705f4fd5da635f298a311ff89840f4f140cfd7fdb8e30a4283c64b9841ada8caf774e18abde82fd70d0aa4
	SHA-1-->f73cc07122121b850df8214904434a9ee72b4fd8
	SHA-384-->9bffd11f61e93f8fa2771a0775eff5edbce63dca1dc09f26b951ea7061fc27a5082dcd967eb3dc501bafacd33bd38734
	Skein-512-512-->df541290e5e602fe44fb7efd26a0e0a1865474397cae248d31d46328183fb779ab9b934f1e5b4980becce1512c65f93d5a17b146d57661cd387e48d10b3c224b
	GOST3411-->0a77326768343ba59c56949b049f462e202484cea8551d480a4de0119bce4f3d
	Skein-1024-512-->025d0799414c48c89940b780a91279b78562f36560d2455c848b2cb5d4165e6a194ebf073e7076a63e63607fb28e0825ff2b361c889605266346a94a74a220bb
	Skein-256-256-->158244f6d90460f72c689bcb0f991474d939b03eefafbfda09995fc4730f080b
	MD5-->5f416b51e894d976d324bb196b24e53f
	MD4-->6f13f20ee4c6bb2ba07a4381eda61fb8
	MD2-->3b15c2aad70938cc5b8e74d1d787c0f5
	Skein-256-224-->4c88eb00f24f7f7c3c0b4bff1703732a3250a302c4f96520f518dc66
	SM3-->1c4cb49e2957bf402b620aee2cadeb808d1680a434a33859c828d54aba4ad960
	Skein-512-160-->2caa9d31ad4ab277fd34a75f7cc3fa9e071ab90b
	Skein-512-128-->30be6b1ab602efe7cfc3d6b0598de81c
	RIPEMD320-->def74b5355bb2e9454a4443ebf4c35eafaad988a5639a6e0b88c43c9a3cbb2e09b67184156a0ae7f
	SHA-256-->52040d1f537016ae78a97d9436a49b6e65d2478182f6aaac813369718fdb59cc
	SHA-224-->38c2514a986f1a5a74ece487c6591599886794a2f59cc38a5a364104
	SHA3-384-->53e3a6c886a4194cd1e96fbc78be7d1be5fee36cb50b1367a4d4d512d5f5db91e2f00593a6cfe862202d2a1650afe2f6
	Skein-256-160-->f344ebcbc1b2aa649917fb02c18f240a2dbf8438
	Skein-256-128-->d9ccd72a52f976dbdde78da90ed710bc
	TIGER-->e83d9dcfa725938501b9b5c9519c58a5d1a2b309ab77b969
	SHA-512-->756499a917b612d7a171ec91f21ef0fff47e851f314100e732868c736c6bf9b60c0f4117b944431c4e61d56046d8ad80998eb36a3f5aa974f449b2ba90ef854d
	SHA-512/256-->aae884f5ac187b37ac3adca7602c967a532888bd4f3f32a0b96f51f1ef7661a6
	SHA-512/224-->793a0fa2c66471ced7e3041187428adfe6efcf77865c246366517405
	RIPEMD256-->8631691022f4686730abb02cdc08defaa0963edcb2f0c06fabee788926dc61f7
	Skein-512-384-->bcf696fa5cd1bfa9a3120823311fb60ecb87ea5f5dac02d34f90635ca677069f04fe027aeac289f721a8d2a3e7ef7ab7
	Skein-1024-384-->739b6e9d544de7fbaf81b2ef2bde0b49a9d999fcc4bf0a1e1ef512a0140a173f9df68f9f9db7cb7bc9aa98ff28da5f02
	Tiger-->e83d9dcfa725938501b9b5c9519c58a5d1a2b309ab77b969
	SHA3-256-->d5d35d04cdb5c729984fb0003f03da8f66dadba53aaa40029939b3fb3fb1b9fc
	SHA3-224-->029480fbe980727e21ff564a0154bab0b48c23bc37bf868dfd154351
	*/
	

	
	
}
