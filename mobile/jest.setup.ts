import { configure } from '@testing-library/react-native';

// findBy*/waitFor: the 1 s default is too tight for cold Babel transforms on small CI runners.
configure({ asyncUtilTimeout: 5000 });
