package org.cryptomator.cryptofs;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.spi.AbstractInterruptibleChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.mockito.Mockito.mock;

public class OpenCryptoFilesTest {

	@Rule
	public ExpectedException thrown = ExpectedException.none();

	private final CryptoFileSystemComponent cryptoFileSystemComponent = mock(CryptoFileSystemComponent.class);
	private final OpenCryptoFileComponent.Builder openCryptoFileComponentBuilder = mock(OpenCryptoFileComponent.Builder.class);
	private final FinallyUtil finallyUtil = mock(FinallyUtil.class);
	private final OpenCryptoFile file = mock(OpenCryptoFile.class);
	private final FileChannel ciphertextFileChannel = Mockito.mock(FileChannel.class);

	private OpenCryptoFiles inTest;

	@Before
	public void setup() throws IOException, ReflectiveOperationException {
		OpenCryptoFileComponent subComponent = mock(OpenCryptoFileComponent.class);
		Mockito.when(subComponent.openCryptoFile()).thenReturn(file);

		Mockito.when(cryptoFileSystemComponent.newOpenCryptoFileComponent()).thenReturn(openCryptoFileComponentBuilder);
		Mockito.when(openCryptoFileComponentBuilder.openOptions(Mockito.any())).thenReturn(openCryptoFileComponentBuilder);
		Mockito.when(openCryptoFileComponentBuilder.path(Mockito.any())).thenReturn(openCryptoFileComponentBuilder);
		Mockito.when(openCryptoFileComponentBuilder.build()).thenReturn(subComponent);

		Mockito.when(file.newFileChannel(Mockito.any())).thenReturn(ciphertextFileChannel);
		Field closeLockField = AbstractInterruptibleChannel.class.getDeclaredField("closeLock");
		closeLockField.setAccessible(true);
		closeLockField.set(ciphertextFileChannel, new Object());

		inTest = new OpenCryptoFiles(cryptoFileSystemComponent, finallyUtil);
	}

	@Test
	public void testGetOrCreate() throws IOException {
		OpenCryptoFileComponent subComponent1 = mock(OpenCryptoFileComponent.class);
		OpenCryptoFile file1 = mock(OpenCryptoFile.class);
		Mockito.when(subComponent1.openCryptoFile()).thenReturn(file1);

		OpenCryptoFileComponent subComponent2 = mock(OpenCryptoFileComponent.class);
		OpenCryptoFile file2 = mock(OpenCryptoFile.class);
		Mockito.when(subComponent2.openCryptoFile()).thenReturn(file2);

		Mockito.when(cryptoFileSystemComponent.newOpenCryptoFileComponent()).thenReturn(openCryptoFileComponentBuilder);
		Mockito.when(openCryptoFileComponentBuilder.build()).thenReturn(subComponent1, subComponent2);

		EffectiveOpenOptions openOptions = mock(EffectiveOpenOptions.class);
		Path p1 = Paths.get("/foo");
		Path p2 = Paths.get("/bar");

		Assert.assertSame(file1, inTest.getOrCreate(p1, openOptions));
		Assert.assertSame(file1, inTest.getOrCreate(p1, openOptions));
		Assert.assertSame(file2, inTest.getOrCreate(p2, openOptions));
		Assert.assertNotSame(file1, file2);
	}

	@Test
	public void testWriteCiphertextFile() throws IOException {
		Path path = Paths.get("/foo");
		EffectiveOpenOptions openOptions = Mockito.mock(EffectiveOpenOptions.class);
		ByteBuffer contents = Mockito.mock(ByteBuffer.class);

		inTest.writeCiphertextFile(path, openOptions, contents);

		ArgumentCaptor<ByteBuffer> bytesWritten = ArgumentCaptor.forClass(ByteBuffer.class);
		Mockito.verify(ciphertextFileChannel).write(bytesWritten.capture());
		Assert.assertEquals(contents, bytesWritten.getValue());
	}

	@Test
	public void testReadCiphertextFile() throws IOException {
		byte[] contents = "hello world".getBytes(StandardCharsets.UTF_8);
		Path path = Paths.get("/foo");
		EffectiveOpenOptions openOptions = Mockito.mock(EffectiveOpenOptions.class);
		Mockito.when(ciphertextFileChannel.size()).thenReturn((long) contents.length);
		Mockito.when(ciphertextFileChannel.read(Mockito.any(ByteBuffer.class))).thenAnswer(invocation -> {
			ByteBuffer buf = invocation.getArgument(0);
			buf.put(contents);
			return contents.length;
		});

		ByteBuffer bytesRead = inTest.readCiphertextFile(path, openOptions, 1337);

		Assert.assertEquals("hello world", StandardCharsets.UTF_8.decode(bytesRead).toString());
	}


	@Test
	public void testTwoPhaseMoveFailsWhenTargetIsOpened() throws IOException {
		EffectiveOpenOptions openOptions = mock(EffectiveOpenOptions.class);
		Path src = Paths.get("/src").toAbsolutePath();
		Path dst = Paths.get("/dst").toAbsolutePath();
		inTest.getOrCreate(dst, openOptions);

		thrown.expect(FileAlreadyExistsException.class);
		inTest.prepareMove(src, dst);
	}

	@Test
	public void testTwoPhaseMoveDoesntChangeAnythingWhenRolledBack() throws IOException {
		EffectiveOpenOptions openOptions = mock(EffectiveOpenOptions.class);
		Path src = Paths.get("/src");
		Path dst = Paths.get("/dst");
		inTest.getOrCreate(src, openOptions);

		Assert.assertTrue(inTest.get(src).isPresent());
		Assert.assertFalse(inTest.get(dst).isPresent());
		try (OpenCryptoFiles.TwoPhaseMove twoPhaseMove = inTest.prepareMove(src, dst)) {
			twoPhaseMove.rollback();
		}
		Assert.assertTrue(inTest.get(src).isPresent());
		Assert.assertFalse(inTest.get(dst).isPresent());
	}

	@Test
	public void testTwoPhaseMoveChangesReferencesWhenCommitted() throws IOException {
		EffectiveOpenOptions openOptions = mock(EffectiveOpenOptions.class);
		Path src = Paths.get("/src").toAbsolutePath();
		Path dst = Paths.get("/dst").toAbsolutePath();
		inTest.getOrCreate(src, openOptions);

		Assert.assertTrue(inTest.get(src).isPresent());
		Assert.assertFalse(inTest.get(dst).isPresent());
		OpenCryptoFile srcFile = inTest.get(src).get();
		try (OpenCryptoFiles.TwoPhaseMove twoPhaseMove = inTest.prepareMove(src, dst)) {
			twoPhaseMove.commit();
		}
		Assert.assertFalse(inTest.get(src).isPresent());
		Assert.assertTrue(inTest.get(dst).isPresent());
		OpenCryptoFile dstFile = inTest.get(dst).get();
		Assert.assertSame(srcFile, dstFile);
	}

}
